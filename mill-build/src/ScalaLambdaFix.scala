package millbuild

import org.objectweb.asm.*

import java.util.zip.{ZipEntry, ZipFile, ZipOutputStream}
import scala.jdk.CollectionConverters.*

/** Jars compiled with Scala 3 before 3.3.7 / 3.7.0 (which includes cats, cats-effect and fs2 today) emit
  * LambdaMetafactory calls whose instantiated method type returns a primitive where the SAM returns a reference
  * (scala/scala3#15736). The JVM tolerates it, but d8 turns it into a `check-cast` to a primitive type, which ART
  * rejects with a VerifyError.
  *
  * This applies the compiler fix (scala/scala3#22632) to the published bytecode: box that return type, in the
  * instantiated method type and in any altMetafactory bridge types, as Scala >= 3.3.7 emits them.
  */
object ScalaLambdaFix {
  private val lambdaMetafactory = "java/lang/invoke/LambdaMetafactory"

  /** Writes a patched copy of `jar` to `out` and returns it, or None when the jar needs no changes. */
  def patchJar(jar: os.Path, out: os.Path): Option[os.Path] = {
    val zip = ZipFile(jar.toIO)
    try {
      val entries = zip.entries().asScala.toVector
      val patched = entries.iterator
        .filter(_.getName.endsWith(".class"))
        .flatMap(e => patchClass(zip.getInputStream(e).readAllBytes()).map(e.getName -> _))
        .toMap
      Option.when(patched.nonEmpty) {
        val zos = ZipOutputStream(os.write.outputStream(out, createFolders = true))
        try
          entries.foreach { e =>
            zos.putNextEntry(ZipEntry(e.getName))
            zos.write(patched.getOrElse(e.getName, zip.getInputStream(e).readAllBytes()))
            zos.closeEntry()
          }
        finally zos.close()
        out
      }
    } finally zip.close()
  }

  /** The patched class bytes, or None when the class has nothing to fix. */
  def patchClass(bytes: Array[Byte]): Option[Array[Byte]] = {
    var changed = false
    val reader  = ClassReader(bytes)
    val writer  = ClassWriter(reader, 0)
    reader.accept(
      new ClassVisitor(Opcodes.ASM9, writer) {
        override def visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String,
            exceptions: Array[String]
        ): MethodVisitor =
          new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
            override def visitInvokeDynamicInsn(
                name: String,
                descriptor: String,
                bsm: Handle,
                bsmArgs: AnyRef*
            ): Unit = {
              // Both factories take (samMethodType, implMethod, instantiatedMethodType, ...); for
              // altMetafactory the tail holds flags, marker classes and bridge method types.
              val args = (bsm.getOwner, bsmArgs.headOption) match {
                case (`lambdaMetafactory`, Some(sam: Type)) if isReference(sam.getReturnType) =>
                  val fixed = bsmArgs.take(2) ++ bsmArgs.drop(2).map(boxReturn)
                  if (fixed != bsmArgs) changed = true
                  fixed
                case _                                                                        => bsmArgs
              }
              super.visitInvokeDynamicInsn(name, descriptor, bsm, args*)
            }
          }
      },
      0
    )
    Option.when(changed)(writer.toByteArray)
  }

  private def isReference(t: Type) = t.getSort == Type.OBJECT || t.getSort == Type.ARRAY

  private def boxReturn(arg: AnyRef): AnyRef = arg match {
    case t: Type if t.getSort == Type.METHOD => Type.getMethodType(boxed(t.getReturnType), t.getArgumentTypes*)
    case other                               => other
  }

  private def boxed(t: Type): Type = t.getSort match {
    case Type.BOOLEAN => Type.getObjectType("java/lang/Boolean")
    case Type.BYTE    => Type.getObjectType("java/lang/Byte")
    case Type.CHAR    => Type.getObjectType("java/lang/Character")
    case Type.SHORT   => Type.getObjectType("java/lang/Short")
    case Type.INT     => Type.getObjectType("java/lang/Integer")
    case Type.LONG    => Type.getObjectType("java/lang/Long")
    case Type.FLOAT   => Type.getObjectType("java/lang/Float")
    case Type.DOUBLE  => Type.getObjectType("java/lang/Double")
    case _            => t
  }
}
