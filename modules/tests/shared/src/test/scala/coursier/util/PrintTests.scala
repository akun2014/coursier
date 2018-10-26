package coursier.util

import coursier.core.{Attributes, Classifier, Configuration, Type}
import coursier.test.{CentralTests, TestRunner}
import coursier.{Dependency, Module, moduleNameString, organizationString}
import utest._

import scala.concurrent.ExecutionContext.Implicits.global

object PrintTests extends TestSuite {

  object AppliedTree {
    def apply[A](tree: Tree[A]): Seq[AppliedTree[A]] = {
      tree.roots.map(root => {
        AppliedTree[A](root, apply(Tree(tree.children(root).toIndexedSeq, tree.children)))
      })
    }
  }

  case class AppliedTree[A](root: A, children: Seq[AppliedTree[A]])

  private val runner = new TestRunner


  val tests = Tests {
    'ignoreAttributes - {
      val dep = Dependency(
        Module(org"org", name"name"),
        "0.1",
        configuration = Configuration("foo")
      )
      val deps = Seq(
        dep,
        dep.copy(attributes = Attributes(Type("fooz"), Classifier.empty))
      )

      val res = Print.dependenciesUnknownConfigs(deps, Map())
      val expectedRes = "org:name:0.1:foo"

      assert(res == expectedRes)
    }

    'reverseTree - {
      val junit = Module(org"junit", name"junit")
      val junitVersion = "4.10"

      runner.resolve(Seq(Dependency(junit, junitVersion))).map(result => {
        val hamcrest = Module(org"org.hamcrest", name"hamcrest-core")
        val hamcrestVersion = "1.1"
        val reverseTree = Print.reverseTree(Seq(Dependency(hamcrest, hamcrestVersion)),
          result, withExclusions = true)

        val applied = AppliedTree.apply(reverseTree)
        assert(applied.length == 1)

        val expectedHead = applied.head
        assert(expectedHead.root.module == hamcrest)
        assert(expectedHead.root.version == hamcrestVersion)
        assert(expectedHead.children.length == 1)

        val expectedChild = expectedHead.children.head
        assert(expectedChild.root.module == junit)
        assert(expectedChild.root.version == junitVersion)
      })
    }
  }

}
