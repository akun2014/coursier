package coursier.core

import scala.scalajs.js
import org.scalajs.dom.raw.NodeList

package object compatibility {
  def option[A](a: js.Dynamic): Option[A] =
    if (js.isUndefined(a)) None
    else Some(a.asInstanceOf[A])
  def dynOption(a: js.Dynamic): Option[js.Dynamic] =
    if (js.isUndefined(a)) None
    else Some(a)

  private def between(c: Char, lower: Char, upper: Char) = lower <= c && c <= upper

  implicit class RichChar(val c: Char) extends AnyVal {
    def letterOrDigit: Boolean = {
      between(c, '0', '9') || letter
    }
    def letter: Boolean = between(c, 'a', 'z') || between(c, 'A', 'Z')
  }

  lazy val DOMParser = {
    import js.Dynamic.{global => g}
    import js.DynamicImplicits._

    val defn =
      if (js.isUndefined(g.DOMParser)) g.require("xmldom").DOMParser
      else g.DOMParser
    js.Dynamic.newInstance(defn)()
  }

  lazy val XMLSerializer = {
    import js.Dynamic.{global => g}
    import js.DynamicImplicits._

    val defn =
      if (js.isUndefined(g.XMLSerializer)) g.require("xmldom").XMLSerializer
      else g.XMLSerializer
    js.Dynamic.newInstance(defn)()
  }

  // Can't find these from node
  val ELEMENT_NODE = 1 // org.scalajs.dom.raw.Node.ELEMENT_NODE
  val TEXT_NODE = 3 // org.scalajs.dom.raw.Node.TEXT_NODE

  def fromNode(node: org.scalajs.dom.raw.Node): Xml.Node = {

    val node0 = node.asInstanceOf[js.Dynamic]

    new Xml.Node {
      def label =
        option[String](node0.nodeName)
          .getOrElse("")
      def child =
        option[NodeList](node0.childNodes)
          .map(l => List.tabulate(l.length)(l.item).map(fromNode))
          .getOrElse(Nil)

      // `exists` instead of `contains`, for scala 2.10
      def isText =
        option[Int](node0.nodeType)
          .exists(_ == TEXT_NODE)
      def textContent =
        option(node0.textContent)
          .getOrElse("")
      def isElement =
        option[Int](node0.nodeType)
          .exists(_ == ELEMENT_NODE)

      override def toString =
        XMLSerializer.serializeToString(node).asInstanceOf[String]
    }
  }


  def xmlParse(s: String): Either[String, Xml.Node] = {
    val doc = {
      if (s.isEmpty) None
      else {
        for {
          xmlDoc <- dynOption(DOMParser.parseFromString(s, "text/xml"))
          rootNodes <- dynOption(xmlDoc.childNodes)
          // From node, rootNodes.head is sometimes just a comment instead of the main root node
          // (tested with org.ow2.asm:asm-commons in CentralTests)
          rootNode <- rootNodes.asInstanceOf[js.Array[js.Dynamic]]
            .flatMap(option[org.scalajs.dom.raw.Node])
            .dropWhile(_.nodeType != ELEMENT_NODE)
            .headOption
        } yield rootNode
      }
    }

    Right(doc.fold(Xml.Node.empty)(fromNode))
  }

}
