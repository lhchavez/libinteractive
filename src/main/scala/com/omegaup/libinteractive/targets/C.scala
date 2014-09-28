// Copyright (c) 2014 The omegaUp Contributors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.omegaup.libinteractive.target

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.FileAlreadyExistsException

import scala.collection.mutable.StringBuilder

import com.omegaup.libinteractive.idl._

class C(idl: IDL, options: Options, input: Path, parent: Boolean)
		extends Target(idl, options) {
	override def extension() = "c"

	def executableExtension() = options.os match {
		case OS.Windows => ".exe"
		case _ => ""
	}

	override def generate() = {
		if (parent) {
			val mainFile = s"${idl.main.name}.$extension"
			List(
				new OutputDirectory(Paths.get(idl.main.name)),
				new OutputLink(Paths.get(idl.main.name, mainFile), input),
				generateMainHeader,
				generateMainFile)
		} else {
			generateTemplates(options.moduleName, idl.interfaces,
					idl.main.name, List(idl.main), input) ++
			idl.interfaces.flatMap(interface =>
				List(
					new OutputDirectory(Paths.get(interface.name)),
					generateHeader(interface),
					generate(interface),
					generateLink(interface, input))
			)
		}
	}

	override def generateMakefileRules() = {
		if (parent) {
			List(MakefileRule(Paths.get(idl.main.name, idl.main.name + executableExtension),
				List(
					Paths.get(idl.main.name, s"${idl.main.name}.$extension"),
					Paths.get(idl.main.name, s"${idl.main.name}_entry.$extension")),
				compiler, s"$cflags -o $$@ $$^ -O2 $ldflags -Wall -D_XOPEN_SOURCE=600"))
		} else {
			idl.interfaces.map(interface =>
				MakefileRule(Paths.get(interface.name, interface.name + executableExtension),
					List(
						Paths.get(interface.name, s"${options.moduleName}.$extension"),
						Paths.get(interface.name, s"${interface.name}_entry.$extension")),
					compiler, s"$cflags -o $$@ $$^ -O2 -Wall -D_XOPEN_SOURCE=600"))
		}
	}

	override def generateRunCommands() = {
		(if (parent) {
			List(idl.main)
		} else {
			idl.interfaces
		}).map(interface =>
			ExecDescription(Array(relativeToRoot(
				options.outputDirectory
					.resolve(Paths.get(interface.name, interface.name + executableExtension)))
				.toString))
		)
	}

	override def generateTemplates(moduleName: String,
			interfacesToImplement: Iterable[Interface], callableModuleName: String,
			callableInterfaces: Iterable[Interface], input: Path): Iterable[OutputPath] = {
		if (!options.generateTemplate) return List.empty[OutputPath]
		if (!options.force && Files.exists(input, LinkOption.NOFOLLOW_LINKS)) {
			throw new FileAlreadyExistsException(input.toString, null,
				"Refusing to overwrite file. Delete it or invoke with --force to override.")
		}

		val builder = new StringBuilder
		builder ++= s"""#include "${options.moduleName}.h"\n\n"""
		for (interface <- callableInterfaces) {
			if (interface.functions.exists(_ => true)) {
				builder ++= s"// ${interface.name}:\n"
				for (function <- interface.functions) {
					builder ++= s"//\t${declareFunction(function)}\n"
				}
				builder ++= "\n"
			}
		}
		for (interface <- interfacesToImplement) {
			for (function <- interface.functions) {
				builder ++= s"\n${declareFunction(function)} {\n"
				builder ++= "\t// FIXME\n"
				if (function.returnType != PrimitiveType("void")) {
					builder ++= s"\treturn ${defaultValue(function.returnType)};\n"
				}
				builder ++= "}\n"
			}
		}

		List(OutputFile(input.toAbsolutePath, builder.mkString))
	}

	def compiler() = Compiler.Gcc

	def cflags() = "-std=c99"

	def ldflags() = options.os match {
		case OS.Unix => "-Wl,-e__entry"
		case OS.Windows => "-Wl,-e___entry"
	}

	private def arrayDim(length: ArrayLength) = s"[${length.value}]"

	private def defaultValue(t: PrimitiveType) = {
		t.name match {
			case "bool" => "false"
			case "char" => "'\\0'"
			case "short" => "0"
			case "int" => "0"
			case "float" => "0.0f"
			case "long" => "0LL"
			case "double" => "0.0"
		}
	}

	private def formatPrimitive(t: PrimitiveType) = {
		t.name match {
			case "long" => "long long"
			case primitive: String => primitive
		}
	}

	private def formatType(t: Type) = {
		t match {
			case arrayType: ArrayType =>
				s"${formatPrimitive(arrayType.primitive)}(*)" +
					arrayType.lengths.tail.map(arrayDim).mkString
			case primitiveType: PrimitiveType =>
				s"${formatPrimitive(primitiveType)}"
		}
	}

	private def formatParam(param: Parameter) = {
		param.paramType match {
			case arrayType: ArrayType =>
				s"${formatPrimitive(arrayType.primitive)} ${param.name}[]" +
					arrayType.lengths.tail.map(arrayDim).mkString
			case primitiveType: PrimitiveType =>
				s"${formatPrimitive(primitiveType)} ${param.name}"
		}
	}

	private def formatLength(length: ArrayLength, function: Option[Function]) = {
		length match {
			case param: ParameterLength if !function.isEmpty =>
				s"${function.get.name}_${param.value}"
			case length: ArrayLength =>
				s"(${length.value})"
		}
	}

	private def fieldLength(fieldType: Type, function: Option[Function] = None) = {
		fieldType match {
			case primitiveType: PrimitiveType =>
				s"sizeof(${formatPrimitive(primitiveType)})"
			case arrayType: ArrayType =>
				s"sizeof(${formatPrimitive(arrayType.primitive)}) * " +
					arrayType.lengths.map(formatLength(_, function)).mkString(" * ")
		}
	}

	private def declareFunction(function: Function) = {
		s"${formatPrimitive(function.returnType)} ${function.name}(" +
			function.params.map(formatParam).mkString(", ") + ")"
	}

	private def declareVar(param: Parameter, function: Function) = {
		param.paramType match {
			case array: ArrayType =>
				s"${formatPrimitive(array.primitive)} (*${function.name}_${param.name})" +
					array.lengths.tail.map(arrayDim).mkString
			case primitive: PrimitiveType =>
				s"${formatPrimitive(primitive)} ${function.name}_${param.name}"
		}
	}

	private def generateHeader(interface: Interface) = {
		val builder = new StringBuilder
		builder ++= s"/* $message */\n\n"
		interface.functions.map(declareFunction(_) + ";\n").foreach(builder ++= _)
		idl.main.functions.map(declareFunction(_) + ";\n").foreach(builder ++= _)
		OutputFile(
			Paths.get(interface.name, s"${options.moduleName}.h"),
			builder.mkString)
	}

	private def generate(interface: Interface) = {
		val builder = new StringBuilder
		builder ++= s"""/* $message */
#include "${options.moduleName}.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>

#if defined(_WIN32)
#if !defined(PRIuS)
#define PRIuS "Iu"
#endif
#else
#if !defined(PRIuS)
#define PRIuS "zu"
#endif
// Windows requires this flag to open files in binary mode using the
// open syscall.
#define O_BINARY 0
#endif

static int __in = -1, __out = -1;

#ifdef __cplusplus
extern "C" {
#endif

static void readfull(int fd, void* buf, size_t count) {
	ssize_t bytes;
	while (count > 0) {
		bytes = read(fd, buf, count);
		if (bytes <= 0) {
			fprintf(stderr, "Incomplete message missing %" PRIuS " bytes\\n", count);
			exit(1);
		}
		buf = bytes + (char*)buf;
		count -= bytes;
	}
}

static void writefull(int fd, const void* buf, size_t count) {
	ssize_t bytes;
	while (count > 0) {
		bytes = write(fd, buf, count);
		if (bytes <= 0) {
			fprintf(stderr, "Incomplete message missing %" PRIuS " bytes\\n", count);
			exit(1);
		}
		buf = bytes + (char*)buf;
		count -= bytes;
	}
}

#ifdef __cplusplus
}
#endif

${generateMessageLoop(List((idl.main, interface, "__out")), "__in")}

int main(int argc, char* argv[]) {
	int retval = 0;

	${if (options.verbose) {
		"\tfprintf(stderr, \"\\t[" + interface.name + "] opening `" +
			pipeFilename(interface, interface) + "'\\n\");\n"
	} else ""}
	if ((__in = open("${pipeFilename(interface, interface)}", O_RDONLY | O_BINARY)) == -1) {
		perror("open");
		retval = 1;
		goto cleanup;
	}
	${if (options.verbose) {
		"\tfprintf(stderr, \"\\t[" + interface.name + "] opening `" +
			pipeFilename(idl.main, interface) + "'\\n\");\n"
	} else ""}
	if ((__out = open("${pipeFilename(idl.main, interface)}", O_WRONLY | O_BINARY)) == -1) {
		perror("open");
		retval = 1;
		goto cleanup;
	}

	__message_loop(-1);

cleanup:
	${if (options.verbose) {
		"\tfprintf(stderr, \"\\t[" + interface.name + "] closing `" +
			pipeFilename(interface, interface) + "'\\n\");\n"
	} else ""}
	if (__in != -1) {
		if (close(__in) == -1) {
			perror("close");
		}
	}
	${if (options.verbose) {
		"\tfprintf(stderr, \"\\t[" + interface.name + "] closing `" +
			pipeFilename(idl.main, interface) + "'\\n\");\n"
	} else ""}
	if (__out != -1) {
		if (close(__out) == -1) {
			perror("close");
		}
	}

	return retval;
}
"""
		for (function <- idl.main.functions) {
			builder ++= generateShim(function, idl.main, interface, "__out", "__in")
		}
		OutputFile(
			Paths.get(interface.name, s"${interface.name}_entry.$extension"),
			builder.mkString)
	}

	private def generateMainHeader() = {
		val builder = new StringBuilder
		builder ++= s"/* $message */\n\n"
		idl.allInterfaces.flatMap(interface => {
			interface.functions.map(declareFunction(_) + ";\n")
		}).foreach(builder ++= _)
		OutputFile(
			Paths.get(idl.main.name, s"${options.moduleName}.h"),
			builder.mkString)
	}

	private def generateMainFile() = {
		val openPipes = idl.interfaces.map(interface => {
			(if (options.verbose) {
				s"""\tfprintf(stderr, "\\t[${idl.main.name}] opening """ +
					s"""`${pipeFilename(interface, idl.main)}'\\n");\n"""
			} else {
				""
			}) +
s"""\tif ((${pipeName(interface)} =
			open("${pipeFilename(interface, idl.main)}", O_WRONLY | O_BINARY)) == -1) {
		perror("open");
		exit(1);
	}\n"""}).mkString("\n") +
			(if (options.verbose) {
				s"""\tfprintf(stderr, "\\t[${idl.main.name}] opening """ +
					s"""`${pipeFilename(idl.main, idl.main)}'\\n");\n"""
			} else {
				""
			}) +
s"""\tif ((${pipeName(idl.main)} =
			open("${pipeFilename(idl.main, idl.main)}", O_RDONLY | O_BINARY)) == -1) {
		perror("open");
		exit(1);
	}\n"""
		val closePipes = (idl.interfaces ++ List(idl.main)).map(interface => {
			(if (options.verbose) {
				s"""\tfprintf(stderr, "\\t[${idl.main.name}] closing """ +
					s"""`${pipeFilename(interface, idl.main)}'\\n");\n"""
			} else {
				""
			}) +
s"""\tif (${pipeName(interface)} != -1) {
		if (close(${pipeName(interface)}) == -1) {
			perror("close");
		}
	}"""}).mkString("\n")
		val builder = new StringBuilder
		builder ++= s"""/* $message */
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>
#include "${options.moduleName}.h"

#if defined(_WIN32)
#if !defined(PRIuS)
#define PRIuS "Iu"
#endif
#else
#if !defined(PRIuS)
#define PRIuS "zu"
#endif
// Windows requires this flag to open files in binary mode using the
// open syscall.
#define O_BINARY 0
#endif

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
// declared in windows.h
void mainCRTStartup();
#else
// declared in crt1.o
void _start();
#endif

void __entry();
static void __exit();
static int ${idl.allInterfaces.map(pipeName).mkString(", ")};

static void readfull(int fd, void* buf, size_t count) {
	ssize_t bytes;
	while (count > 0) {
		bytes = read(fd, buf, count);
		if (bytes <= 0) {
			fprintf(stderr, "Incomplete message missing %" PRIuS " bytes\\n", count);
			exit(1);
		}
		buf = bytes + (char*)buf;
		count -= bytes;
	}
}

static void writefull(int fd, const void* buf, size_t count) {
	ssize_t bytes;
	while (count > 0) {
		bytes = write(fd, buf, count);
		if (bytes <= 0) {
			fprintf(stderr, "Incomplete message missing %" PRIuS " bytes\\n", count);
			exit(1);
		}
		buf = bytes + (char*)buf;
		count -= bytes;
	}
}

#ifdef __cplusplus
}
#endif

void __entry() {
$openPipes

	// Register __exit to be called when main returns.
	atexit(__exit);
	// Perform regular libc startup
	#if defined(_WIN32)
	mainCRTStartup();
	#else
	_start();
	#endif
}

void __exit() {
$closePipes
}

"""
		builder ++= generateMessageLoop(
			idl.interfaces.map{
				interface => (interface, idl.main, pipeName(interface))
			},
			pipeName(idl.main)
		)
		idl.interfaces.foreach(interface => {
			interface.functions.foreach(
				builder ++= generateShim(_, interface, idl.main, pipeName(interface),
					pipeName(idl.main))
			)
		})
		
		OutputFile(
			Paths.get(idl.main.name, s"${idl.main.name}_entry.$extension"),
			builder.mkString)
	}

	private def generateMessageLoop(interfaces: List[(Interface, Interface, String)],
			infd: String) = {
		val builder = new StringBuilder
		builder ++= s"""static void __message_loop(int __current_function) {
	int __msgid;
	while (read($infd, &__msgid, sizeof(int)) == sizeof(int)) {
		if (__msgid == __current_function) return;
		switch (__msgid) {\n"""
		for ((caller, callee, outfd) <- interfaces) {
			for (function <- callee.functions) {
				builder ++= f"\t\t\tcase 0x${functionIds((caller.name, callee.name,
					function.name))}%x: {\n"
				builder ++= s"\t\t\t\t// ${caller.name} -> ${callee.name}.${function.name}\n"
				if (options.verbose) {
					builder ++=
						s"""\t\t\t\tfprintf(stderr, "\\t[${callee.name}] """ +
							s"""calling ${function.name} begin\\n");\n"""
				}
				for (param <- function.params) {
					builder ++= (param.paramType match {
						case array: ArrayType => {
							s"\t\t\t\t${declareVar(param, function)} = (${formatType(array)})" +
							s"malloc(${fieldLength(array, Some(function))});\n" +
							s"\t\t\t\treadfull($infd, ${function.name}_${param.name}, " +
							s"${fieldLength(array, Some(function))});\n"
						}
						case primitive: PrimitiveType => {
							s"\t\t\t\t${declareVar(param, function)};\n" +
							s"\t\t\t\treadfull($infd, &${function.name}_${param.name}, " +
							s"${fieldLength(primitive, Some(function))});\n"
						}
					})
				}
				builder ++= s"\t\t\t\tint __cookie;\n"
				builder ++= s"\t\t\t\treadfull($infd, &__cookie, sizeof(int));\n"
				builder ++= (if (function.returnType == PrimitiveType("void")) {
					"\t\t\t\t"
				} else {
					s"\t\t\t\t${formatType(function.returnType)} result = "
				})
				builder ++=
					s"""${function.name}(${function.params.map(function.name + "_" + _.name).mkString(", ")});\n"""
				builder ++= s"\t\t\t\twritefull($outfd, &__msgid, sizeof(int));\n"
				if (function.returnType != PrimitiveType("void")) {
					builder ++= s"\t\t\t\twritefull($outfd, &result, sizeof(result));\n"
				}
				builder ++= s"\t\t\t\twritefull($outfd, &__cookie, sizeof(int));\n"
				for (param <- function.params) {
					param.paramType match {
						case array: ArrayType => {
							builder ++= s"\t\t\t\tfree(${function.name}_${param.name});\n"
						}
						case _ => {}
					}
				}
				if (options.verbose) {
					builder ++=
						s"""\t\t\t\tfprintf(stderr, "\\t[${callee.name}] """ +
							s"""calling ${function.name} end\\n");\n"""
				}
				builder ++= "\t\t\t\tbreak;\n"
				builder ++= "\t\t\t}\n"
			}
		}
		builder ++= """			default: {
				fprintf(stderr, "Unknown message id 0x%x\n", __msgid);
				exit(1);
			}
		}
	}
	if (__current_function != -1) {
		fprintf(stderr, "Confused about exiting\n");
		exit(1);
	}
}
"""
		builder
	}

	private def generateShim(function: Function, callee: Interface, caller: Interface,
			outfd: String, infd: String) = {
		val builder = new StringBuilder
		builder ++= declareFunction(function)
		builder ++= " {\n"
		if (options.verbose) {
			builder ++=
				s"""\tfprintf(stderr, "\\t[${caller.name}] """ +
				s"""invoking ${function.name} begin\\n");\n"""
		}
		builder ++= "\tconst int __msgid = "
		builder ++= f"0x${functionIds((caller.name, callee.name, function.name))}%x;\n"
		builder ++= s"\twritefull($outfd, &__msgid, sizeof(int));\n"
		function.params.foreach(param => {
			builder ++= (param.paramType match {
				case _: PrimitiveType =>
					s"\twritefull($outfd, &${param.name}, " +
					s"${fieldLength(param.paramType)});\n"
				case _: ArrayType =>
					s"\twritefull($outfd, ${param.name}, " +
					s"${fieldLength(param.paramType)});\n"
			})
		})
		builder ++= f"\tint __cookie = 0x${rand.nextInt}%x;\n"
		builder ++= s"\twritefull($outfd, &__cookie, sizeof(__cookie));\n"
		builder ++= "\t__message_loop(__msgid);\n"
		if (function.returnType != PrimitiveType("void")) {
			builder ++= s"\t${formatType(function.returnType)} __ans = 0;\n"
			builder ++= s"\treadfull($infd, &__ans, sizeof(__ans));\n"
		}
		builder ++= "\tint __cookie_result = 0;\n"
		builder ++= s"\treadfull($infd, &__cookie_result, sizeof(int));\n"

		builder ++= "\tif (__cookie != __cookie_result) {\n"
		builder ++= "\t\tfprintf(stderr, \"invalid __cookie\\n\");\n"
		builder ++= "\t\texit(1);\n"
		builder ++= "\t}\n"

		if (options.verbose) {
			builder ++=
				s"""\tfprintf(stderr, "\\t[${caller.name}] """ +
				s"""invoking ${function.name} end\\n");\n"""
		}

		if (function.returnType != PrimitiveType("void")) {
			builder ++= "\treturn __ans;\n"
		}
		builder ++= "}\n"

		builder
	}
}

class Cpp(idl: IDL, options: Options, input: Path, parent: Boolean)
		extends C(idl, options, input, parent) {
	override def extension() = "cpp"

	override def compiler() = Compiler.Gxx

	override def cflags() = "-std=c++11"
}

/* vim: set noexpandtab: */
