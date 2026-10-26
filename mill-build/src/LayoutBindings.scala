package millbuild

import org.w3c.dom.{Element, Node}

import javax.xml.parsers.DocumentBuilderFactory

/** Generates a typed binding per layout from the XML files in res/layout and its variants (res/layout-w600dp and
  * friends), similar to Android View Binding.
  *
  * For res/layout/home.xml with `@+id/status` (TextView) it emits
  *
  * {{{
  * final class HomeLayout(val rootView: View, val status: android.widget.TextView) extends Layout
  * object HomeLayout extends LayoutBinding[HomeLayout]:
  *   val layoutId: Int = R.layout.home
  *   def bind(rootView: View): HomeLayout = ...
  *   object id:
  *     val status: ViewId[android.widget.TextView] = ViewId(R.id.status)
  * }}}
  *
  * `HomeLayout.id.status` mirrors `R.id.status` but is scoped to the layout and typed with the view class. Code that
  * holds no inflated layout, such as instrumented tests, refers to views through it.
  *
  * Ids present in only some variants (layout-w600dp and friends) become Option fields. Ids whose view class differs
  * between variants are typed as android.view.View.
  */
object LayoutBindings {
  private val androidNs = "http://schemas.android.com/apk/res/android"

  private val keywords = Set(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "enum",
    "export",
    "extends",
    "false",
    "final",
    "finally",
    "for",
    "given",
    "if",
    "implicit",
    "import",
    "lazy",
    "match",
    "new",
    "null",
    "object",
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "then",
    "throw",
    "trait",
    "true",
    "try",
    "type",
    "val",
    "var",
    "while",
    "with",
    "yield"
  )

  def generate(resDirs: Seq[os.Path], namespace: String, out: os.Path): Unit = {
    val layoutFiles = for {
      res  <- resDirs if os.exists(res)
      dir  <- os.list(res) if os.isDir(dir) && (dir.last == "layout" || dir.last.startsWith("layout-"))
      file <- os.list(dir) if file.ext == "xml"
    } yield file

    val pkg = s"$namespace.layouts"
    val dir = pkg.split('.').foldLeft(out)(_ / _)
    os.write.over(dir / "LayoutBinding.scala", bindingTrait(pkg), createFolders = true)
    layoutFiles.groupBy(_.baseName).toSeq.sortBy(_._1).foreach { (layout, files) =>
      val variants = files.sortBy(_.toString).map(ids)
      os.write.over(dir / s"${typeName(layout)}.scala", source(pkg, namespace, layout, variants), createFolders = true)
    }
  }

  private def ids(file: os.Path): Seq[ViewId] = {
    val factory                 = DocumentBuilderFactory.newInstance()
    factory.setNamespaceAware(true)
    val doc                     = factory.newDocumentBuilder().parse(file.toIO)
    val found                   = scala.collection.mutable.LinkedHashMap.empty[String, String]
    def visit(node: Node): Unit = node match {
      case e: Element =>
        val id       = e.getAttributeNS(androidNs, "id")
        if ((id.startsWith("@+id/") || id.startsWith("@id/")) && !found.contains(id.drop(id.indexOf('/') + 1)))
          found(id.drop(id.indexOf('/') + 1)) = viewClass(e)
        val children = e.getChildNodes
        (0 until children.getLength).foreach(i => visit(children.item(i)))
      case _          => ()
    }
    visit(doc.getDocumentElement)
    found.toSeq.map(ViewId(_, _))
  }

  private def viewClass(e: Element): String = e.getTagName match {
    case "include" | "fragment" | "merge"                            => "android.view.View"
    case "view"                                                      => Option(e.getAttribute("class")).filter(_.nonEmpty).getOrElse("android.view.View")
    case tag if tag.contains('.')                                    => tag
    case tag @ ("View" | "ViewStub" | "SurfaceView" | "TextureView") => s"android.view.$tag"
    case "WebView"                                                   => "android.webkit.WebView"
    case tag                                                         => s"android.widget.$tag"
  }

  private def source(pkg: String, namespace: String, layout: String, variants: Seq[Seq[ViewId]]): String = {
    val tpe    = typeName(layout)
    val fields = variants.flatMap(_.map(_.name)).distinct.map { name =>
      val classes = variants.map(_.find(_.name == name).map(_.cls))
      val cls     = classes.flatten.distinct match {
        case Seq(one) => one
        case _        => "android.view.View"
      }
      (name, cls, classes.exists(_.isEmpty))
    }
    val params = fields.map { (name, cls, optional) =>
      s"    val ${field(name)}: ${if (optional) s"Option[$cls]" else cls}"
    }
    val ids    = fields.map { (name, cls, _) =>
      s"    val ${field(name)}: ViewId[$cls] = ViewId($namespace.R.id.$name)"
    }
    val args   = fields.map { (name, cls, optional) =>
      val lookup = s"rootView.findViewById[$cls]($namespace.R.id.$name)"
      if (optional) s"      Option($lookup)" else s"""      java.util.Objects.requireNonNull($lookup, "$name")"""
    }
    s"""|// Generated from res/layout*/$layout.xml by mill-build/src/LayoutBindings.scala. Do not edit.
        |package $pkg
        |
        |import android.view.View
        |
        |final class $tpe(
        |    val rootView: View${params.map(",\n" + _).mkString}
        |) extends Layout
        |
        |object $tpe extends LayoutBinding[$tpe]:
        |  val layoutId: Int = $namespace.R.layout.$layout
        |
        |  def bind(rootView: View): $tpe =
        |    $tpe(
        |      rootView${args.map(",\n" + _).mkString}
        |    )
        |${if (ids.isEmpty) "" else ids.mkString("\n  object id:\n", "\n", "\n")}""".stripMargin
  }

  private def bindingTrait(pkg: String): String =
    s"""|// Generated by mill-build/src/LayoutBindings.scala. Do not edit.
        |package $pkg
        |
        |import android.view.{LayoutInflater, View, ViewGroup}
        |
        |/** An inflated layout with typed fields for its views. */
        |trait Layout:
        |  def rootView: View
        |
        |/** A layout resource with typed lookups for its views. */
        |trait LayoutBinding[A <: Layout]:
        |  def layoutId: Int
        |  def bind(rootView: View): A
        |
        |  /** Inflates without attaching to `parent`. Layout params come from `parent`. */
        |  def inflate(parent: ViewGroup): A =
        |    bind(LayoutInflater.from(parent.getContext).inflate(layoutId, parent, false))
        |
        |  /** Inflates without a parent, for an Activity root. setContentView applies match_parent. */
        |  def inflate(inflater: LayoutInflater): A =
        |    bind(inflater.inflate(layoutId, null))
        |
        |/** A view id typed with the class of its view. An Int at runtime. */
        |opaque type ViewId[+V <: View] = Int
        |
        |object ViewId:
        |  def apply[V <: View](value: Int): ViewId[V] = value
        |
        |  extension (id: ViewId[View]) def value: Int = id
        |""".stripMargin

  private def camel(s: String): String = {
    val parts = s.split("[_\\-]").filter(_.nonEmpty)
    (parts.head +: parts.tail.toSeq.map(_.capitalize)).mkString
  }

  private def field(id: String): String = {
    val name = camel(id)
    if (keywords(name)) s"`$name`" else name
  }

  private def typeName(layout: String): String = camel(layout).capitalize + "Layout"

  private final case class ViewId(name: String, cls: String)
}
