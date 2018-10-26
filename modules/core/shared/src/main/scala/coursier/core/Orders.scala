package coursier.core

object Orders {

  trait PartialOrdering[T] extends scala.math.PartialOrdering[T] {
    def lteq(x: T, y: T): Boolean =
      tryCompare(x, y)
        .exists(_ <= 0)
  }

  /** All configurations that each configuration extends, including the ones it extends transitively */
  def allConfigurations(configurations: Map[Configuration, Seq[Configuration]]): Map[Configuration, Set[Configuration]] = {
    def allParents(config: Configuration): Set[Configuration] = {
      def helper(configs: Set[Configuration], acc: Set[Configuration]): Set[Configuration] =
        if (configs.isEmpty)
          acc
        else if (configs.exists(acc))
          helper(configs -- acc, acc)
        else if (configs.exists(!configurations.contains(_))) {
          val (remaining, notFound) = configs.partition(configurations.contains)
          helper(remaining, acc ++ notFound)
        } else {
          val extraConfigs = configs.flatMap(configurations)
          helper(extraConfigs, acc ++ configs)
        }

      helper(Set(config), Set.empty)
    }

    configurations
      .keys
      .toList
      .map(config => config -> (allParents(config) - config))
      .toMap
  }

  /**
    * Configurations partial order based on configuration mapping `configurations`.
    *
    * @param configurations: for each configuration, the configurations it directly extends.
    */
  def configurationPartialOrder(configurations: Map[Configuration, Seq[Configuration]]): PartialOrdering[Configuration] =
    new PartialOrdering[Configuration] {
      val allParentsMap = allConfigurations(configurations)

      def tryCompare(x: Configuration, y: Configuration) =
        if (x == y)
          Some(0)
        else if (allParentsMap.get(x).exists(_(y)))
          Some(-1)
        else if (allParentsMap.get(y).exists(_(x)))
          Some(1)
        else
          None
    }

  /** Non-optional < optional */
  val optionalPartialOrder: PartialOrdering[Boolean] =
    new PartialOrdering[Boolean] {
      def tryCompare(x: Boolean, y: Boolean) =
        Some(
          if (x == y) 0
          else if (x) 1
          else -1
        )
    }

  /**
   * Exclusions partial order.
   *
   * x <= y iff all that x excludes is also excluded by y.
   * x and y not related iff x excludes some elements not excluded by y AND
   *                         y excludes some elements not excluded by x.
   *
   * In particular, no exclusions <= anything <= Set(("*", "*"))
   */
  val exclusionsPartialOrder: PartialOrdering[Set[(Organization, ModuleName)]] =
    new PartialOrdering[Set[(Organization, ModuleName)]] {
      def boolCmp(a: Boolean, b: Boolean) = (a, b) match {
        case (true, true) => Some(0)
        case (true, false) => Some(1)
        case (false, true) => Some(-1)
        case (false, false) => None
      }

      def tryCompare(x: Set[(Organization, ModuleName)], y: Set[(Organization, ModuleName)]) = {
        val (xAll, xExcludeByOrg1, xExcludeByName1, xRemaining0) = Exclusions.partition(x)
        val (yAll, yExcludeByOrg1, yExcludeByName1, yRemaining0) = Exclusions.partition(y)

        boolCmp(xAll, yAll).orElse {
          def filtered(e: Set[(Organization, ModuleName)]) =
            e.filter{case (org, name) =>
              !xExcludeByOrg1(org) && !yExcludeByOrg1(org) &&
                !xExcludeByName1(name) && !yExcludeByName1(name)
            }

          def removeIntersection[T](a: Set[T], b: Set[T]) =
            (a -- b, b -- a)

          def allEmpty(set: Set[_]*) = set.forall(_.isEmpty)

          val (xRemaining1, yRemaining1) =
            (filtered(xRemaining0), filtered(yRemaining0))

          val (xProperRemaining, yProperRemaining) =
            removeIntersection(xRemaining1, yRemaining1)

          val (onlyXExcludeByOrg, onlyYExcludeByOrg) =
            removeIntersection(xExcludeByOrg1, yExcludeByOrg1)

          val (onlyXExcludeByName, onlyYExcludeByName) =
            removeIntersection(xExcludeByName1, yExcludeByName1)

          val (noXProper, noYProper) = (
            allEmpty(xProperRemaining, onlyXExcludeByOrg, onlyXExcludeByName),
            allEmpty(yProperRemaining, onlyYExcludeByOrg, onlyYExcludeByName)
          )

          boolCmp(noYProper, noXProper) // order matters
        }
      }
    }

  private def fallbackConfigIfNecessary(dep: Dependency, configs: Set[Configuration]): Dependency =
    Parse.withFallbackConfig(dep.configuration) match {
      case Some((main, fallback)) =>
        val config0 =
          if (configs(main))
            main
          else if (configs(fallback))
            fallback
          else
            dep.configuration

        dep.copy(configuration = config0)
      case _ =>
        dep
    }

  private def core(dep: Dependency): Dependency =
    dep.copy(configuration = Configuration.empty, exclusions = Set.empty, optional = false)

  private def compareUnsafe(
    xDep: Dependency,
    yDep: Dependency,
    configs: Map[Configuration, Seq[Configuration]]
  ): Option[Int] = {

    val xOpt = xDep.optional
    val yOpt = yDep.optional

    val availableConfigs = configs.keySet
    val xScope = fallbackConfigIfNecessary(xDep, availableConfigs).configuration
    val yScope = fallbackConfigIfNecessary(yDep, availableConfigs).configuration

    for {
      optCmp <- optionalPartialOrder.tryCompare(xOpt, yOpt)
      scopeCmp <- configurationPartialOrder(configs).tryCompare(xScope, yScope)
      if optCmp*scopeCmp >= 0
      exclCmp <- exclusionsPartialOrder.tryCompare(xDep.exclusions, yDep.exclusions)
      if optCmp*exclCmp >= 0
      if scopeCmp*exclCmp >= 0
      xIsMin = optCmp < 0 || scopeCmp < 0 || exclCmp < 0
      yIsMin = optCmp > 0 || scopeCmp > 0 || exclCmp > 0
    } yield if (xIsMin) -1 else if (yIsMin) 1 else 0
  }

  def compare(
    a: Dependency,
    b: Dependency,
    configs: ((Module, String)) => Map[Configuration, Seq[Configuration]]
  ): Option[Int] = {
    val a0 = core(a)
    val b0 = core(b)
    if (a0 == b0)
      compareUnsafe(a, b, configs(a0.moduleVersion))
    else
      None
  }

  def removeRedundancies(
    deps: Seq[Dependency],
    configs: ((Module, String)) => Map[Configuration, Seq[Configuration]]
  ): Seq[Dependency] =
    deps
      .map(dep => fallbackConfigIfNecessary(dep, configs(dep.moduleVersion).keySet))
      .scanLeft((Map.empty[Dependency, List[Dependency]], Option.empty[Dependency])) {
        case ((map, _), dep) =>

          val c = core(dep)

          val (l, keep) = map
            .get(c)
            .fold((List.empty[Dependency], true)) { l0 =>
              val comparisons = l0.toStream.map(d0 => (d0, compare(d0, dep, configs)))
              if (comparisons.exists(_._2.exists(_ <= 0)))
                (l0, false)
              else {
                val l1 = comparisons
                  .collect {
                    case (elem, None) => elem
                  }
                  .toList
                (l1, true)
              }
            }

          if (keep)
            (map + (c -> (dep :: l)), Some(dep))
          else
            (map, None)
      }
      .flatMap(_._2)

  /**
   * Assume all dependencies have same `module`, `version`, and `artifact`; see `minDependencies`
   * if they don't.
   */
  def minDependenciesUnsafe(
    dependencies: Set[Dependency],
    configs: Map[Configuration, Seq[Configuration]]
  ): Set[Dependency] = {
    val availableConfigs = configs.keySet
    val groupedDependencies = dependencies
      .map(fallbackConfigIfNecessary(_, availableConfigs))
      .groupBy(dep => (dep.optional, dep.configuration))
      .mapValues(deps => deps.head.copy(exclusions = deps.foldLeft(Exclusions.one)((acc, dep) => Exclusions.meet(acc, dep.exclusions))))
      .toList

    val remove =
      for {
        List((_, xDep), (_, yDep)) <- groupedDependencies.combinations(2)
        rmDep <- compareUnsafe(xDep, yDep, configs).flatMap {
          case n if n < 0 => Some(yDep)
          case 0          => None
          case n if n > 0 => Some(xDep)
        }
      } yield rmDep

    groupedDependencies.map(_._2).toSet -- remove
  }

  /**
   * Minified representation of `dependencies`.
   *
   * The returned set brings exactly the same things as `dependencies`, with no redundancy.
   */
  def minDependencies(
    dependencies: Set[Dependency],
    configs: ((Module, String)) => Map[Configuration, Seq[Configuration]]
  ): Set[Dependency] = {
    dependencies
      .groupBy(core)
      .mapValues(deps => minDependenciesUnsafe(deps, configs(deps.head.moduleVersion)))
      .valuesIterator
      .fold(Set.empty)(_ ++ _)
  }

}
