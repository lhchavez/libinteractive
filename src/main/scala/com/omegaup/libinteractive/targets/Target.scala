// Copyright (c) 2014 The omegaUp Contributors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.omegaup.libinteractive.target

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Random

import scala.collection.JavaConversions.asJavaIterable
import scala.collection.mutable.MutableList

import com.omegaup.libinteractive.idl.IDL
import com.omegaup.libinteractive.idl.Interface

// DIY enum type, from https://gist.github.com/viktorklang/1057513
trait Enum {
	import java.util.concurrent.atomic.AtomicReference

	// This is a type that needs to be found in the implementing class
	type EnumVal <: Value

	// Stores our enum values
	private val _values = new AtomicReference(Vector[EnumVal]())

	// Adds an EnumVal to our storage, uses CCAS to make sure it's thread safe, returns
	// the ordinal
	private final def addEnumVal(newVal: EnumVal): Int = {
		import _values.{get, compareAndSet => CAS}

		val oldVec = get
		val newVec = oldVec :+ newVal
		if ((get eq oldVec) && CAS(oldVec, newVec)) {
			newVec.indexWhere(_ eq newVal)
		} else {
			addEnumVal(newVal)
		}
	}

	// Here you can get all the enums that exist for this type
	def values: Vector[EnumVal] = _values.get

	// This is the trait that we need to extend our EnumVal type with, it does the
	// book-keeping for us
	protected trait Value { self: EnumVal => // Enforce that no one mixes in Value in a
	                                         // non-EnumVal type
		// Adds the EnumVal and returns the ordinal
		final val ordinal = addEnumVal(this)

		// All enum values should have a name
		def name: String

		def find(name: String): Option[EnumVal] = values.find(_.name == name)
		// And that name is used for the toString operation
		override def toString = name
		override def equals(other: Any) = this eq other.asInstanceOf[AnyRef]
		override def hashCode = 31 * (this.getClass.## + name.## + ordinal)
	}
}

object Command extends Enum {
	sealed trait EnumVal extends Value

	val Verify = new EnumVal { val name = "verify" }
	val Generate = new EnumVal { val name = "generate" }
}

object OS extends Enum {
	sealed trait EnumVal extends Value

	val Unix = new EnumVal { val name = "unix" }
	val Windows = new EnumVal { val name = "windows" }
}

case class Options(
	childLang: String = "c",
	command: Command.EnumVal = Command.Verify,
	force: Boolean = false,
	generateTemplate: Boolean = false,
	idlFile: Path = null,
	makefile: Boolean = false,
	moduleName: String = "",
	outputDirectory: Path = Paths.get("libinteractive"),
	os: OS.EnumVal = OS.Unix,
	root: Path = Paths.get(".").normalize,
	parentLang: String = "c",
	pipeDirectories: Boolean = false,
	quiet: Boolean = false,
	seed: Long = System.currentTimeMillis,
	sequentialIds: Boolean = false,
	verbose: Boolean = false
)

abstract class OutputPath(path: Path) {
	def install(root: Path): Unit
}

case class OutputDirectory(path: Path) extends OutputPath(path) {
	override def install(root: Path) {
		val directory = root.resolve(path).normalize
		if (!Files.exists(directory)) {
			Files.createDirectories(directory)
		}
	}
}

case class OutputFile(path: Path, contents: String) extends OutputPath(path) {
	override def install(root: Path) {
		Files.write(root.resolve(path), List(contents), StandardCharsets.UTF_8)
	}
}

case class OutputMakefile(path: Path, contents: String) extends OutputPath(path) {
	override def install(root: Path) {
		val directory = path.getParent
		if (directory != null && !Files.exists(directory)) {
			Files.createDirectories(directory)
		}
		Files.write(path, List(contents), StandardCharsets.UTF_8)
	}
}

case class OutputLink(path: Path, target: Path) extends OutputPath(path) {
	override def install(root: Path) {
		val link = root.resolve(path)
		if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
			Files.createSymbolicLink(link, link.getParent.relativize(target))
		}
	}
}

case class ResolvedOutputLink(link: Path, target: Path)

abstract class OutputPathFilter {
	def apply(input: OutputPath): Option[OutputPath]
}

object NoOpFilter extends OutputPathFilter {
	override def apply(input: OutputPath) = Some(input)
}

abstract class LinkFilter extends OutputPathFilter {
	def resolvedLinks(): Iterable[ResolvedOutputLink]
}

object NoOpLinkFilter extends LinkFilter {
	override def resolvedLinks() = List.empty[ResolvedOutputLink]
	override def apply(input: OutputPath) = Some(input)
}

class WindowsLinkFilter(root: Path) extends LinkFilter {
	private val links = MutableList.empty[ResolvedOutputLink]

	override def resolvedLinks() = links
	override def apply(input: OutputPath) = {
		input match {
			case link: OutputLink => {
				val resolvedLink = root.resolve(link.path)
				links += ResolvedOutputLink(
					resolvedLink, link.target)
				None
			}
			case path: OutputPath => Some(path)
		}
	}
}

object WindowsLineEndingFilter extends OutputPathFilter {
	override def apply(input: OutputPath) = {
		input match {
			case file: OutputFile =>
				Some(new OutputFile(file.path, file.contents.replace("\n", "\r\n")))
			case file: OutputMakefile =>
				Some(new OutputMakefile(file.path, file.contents.replace("\n", "\r\n")))
			case path: OutputPath => Some(path)
		}
	}
}

object Compiler extends Enumeration {
	type Compiler = Value
	val Gcc = Value("gcc")
	val Gxx = Value("g++")
	val Fpc = Value("fpc")
	val Javac = Value("javac")
	val Python = Value("python")
	val Ruby = Value("ruby")
}
import Compiler.Compiler

case class MakefileRule(target: Path, requisites: Iterable[Path], compiler: Compiler,
		params: String)

case class ExecDescription(args: Array[String], env: Map[String, String] = Map())

abstract class Target(idl: IDL, options: Options) {
	protected val message = "Auto-generated by libinteractive. Do not edit."
	protected val rand = new Random(options.seed)
	protected val functionIds = idl.interfaces.flatMap (interface => {
		interface.functions.map(
			function => (idl.main.name, interface.name, function.name) -> nextId) ++
		idl.main.functions.map(
			function => (interface.name, idl.main.name, function.name) -> nextId)
	}).toMap

	private var currentId = 0
	private def nextId() = {
		if (options.sequentialIds) {
			currentId += 1
			currentId
		} else {
			rand.nextInt
		}
	}

	protected def pipeFilename(interface: Interface, caller: Interface) = {
		if (options.pipeDirectories) {
			s"${interface.name}_pipes/pipe"
		} else {
			options.os match {
				case OS.Unix => interface.name match {
					case "Main" => "out"
					case name: String => s"${name}_in"
				}
				case OS.Windows => s"\\\\\\\\.\\\\pipe\\\\${caller.name}_" + (
					interface.name match {
						case "Main" => "out"
						case name: String => s"${name}_in"
					}
				)
			}
		}
	}

	protected def pipeName(interface: Interface) = {
		interface.name match {
			case "Main" => "__out"
			case name: String => s"__${name}_in"
		}
	}

	protected def relativeToRoot(path: Path) = {
		if (options.root.toString.length == 0) {
			path
		} else {
			options.root.relativize(path)
		}
	}

	def generate(): Iterable[OutputPath]
	def extension(): String
	def generateMakefileRules(): Iterable[MakefileRule]
	def generateRunCommands(): Iterable[ExecDescription]

	protected def generateLink(interface: Interface, input: Path): OutputPath = {
		val moduleFile = s"${options.moduleName}.$extension"
		new OutputLink(Paths.get(interface.name, moduleFile), input)
	}
	protected def generateTemplates(moduleName: String,
			interfacesToImplement: Iterable[Interface], callableModuleName: String,
			callableInterfaces: Iterable[Interface], input: Path): Iterable[OutputPath]
}

object Generator {
	def generate(idl: IDL, options: Options, problemsetter: Path, contestant: Path) = {
		val parent = target(options.parentLang, idl, options, problemsetter, true)
		val child = target(options.childLang, idl, options, contestant, false)

		val originalTargets = List(parent, child)
		val originalOutputs = originalTargets.flatMap(_.generate)
		val outputs = if (options.makefile) {
			val filter = options.os match {
				case OS.Unix => NoOpLinkFilter
				case OS.Windows => new WindowsLinkFilter(options.outputDirectory)
			}
			val filteredOutputs = originalOutputs.flatMap(filter.apply)
			filteredOutputs ++ new Makefile(idl,
				originalTargets.flatMap(_.generateMakefileRules),
				originalTargets.flatMap(_.generateRunCommands),
				filter.resolvedLinks, options).generate
		} else {
			originalOutputs
		}

		val filter = options.os match {
			case OS.Unix => NoOpFilter
			case OS.Windows => WindowsLineEndingFilter
		}

		outputs.flatMap(filter.apply)
	}

	def target(lang: String, idl: IDL, options: Options, input: Path,
			parent: Boolean): Target = {
		lang match {
			case "c" => new C(idl, options, input, parent)
			case "cpp" => new Cpp(idl, options, input, parent)
			case "cpp11" => new Cpp(idl, options, input, parent)
			case "java" => new Java(idl, options, input, parent)
			case "pas" => new Pascal(idl, options, input, parent)
			case "py" => new Python(idl, options, input, parent)
			case "rb" => new Ruby(idl, options)
		}
	}
}

/* vim: set noexpandtab: */
