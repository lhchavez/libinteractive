package com.omegaup.libinteractive.target

import scala.collection.mutable.StringBuilder

import com.omegaup.libinteractive.idl._

class Python(idl: IDL, options: Options, parent: Boolean) extends Target(idl, options) {
	override def generate() = {
		if (parent) {
			List(generateMainLib(), generateMain())
		} else {
			idl.interfaces.flatMap(interface =>
				List(generateLib(interface), generate(interface))
			)
		}
	}

	override def generateMakefileRules() = {
		List.empty[MakefileRule]
	}

	override def generateRunCommands() = {
		(if (parent) {
			List(idl.main)
		} else {
			idl.interfaces
		}).map(interface =>
			Array("/usr/bin/python", s"${interface.name}_entry.py")
		)
	}

	def structFormat(formatType: Type): String = {
		formatType match {
			case primitiveType: PrimitiveType => primitiveType match {
				case PrimitiveType("int") => "'i'"
				case PrimitiveType("long") => "'q'"
				case PrimitiveType("char") => "'c'"
				case PrimitiveType("float") => "'f'"
				case PrimitiveType("double") => "'d'"
				case PrimitiveType("bool") => "'?'"
			}
			case arrayType: ArrayType => {
				"'%d" + structFormat(arrayType.primitive).charAt(1) + "' % " +
						arrayLength(arrayType)
			}
		}
	}

	private def arrayLength(arrayType: ArrayType) = {
			arrayType.lengths.map(_.value).mkString(" * ")
	}

	private def fieldLength(fieldType: Type): String = {
		fieldType match {
			case primitiveType: PrimitiveType =>
				primitiveType match {
					case PrimitiveType("int") => "4"
					case PrimitiveType("long") => "8"
					case PrimitiveType("char") => "1"
					case PrimitiveType("float") => "4"
					case PrimitiveType("double") => "8"
					case PrimitiveType("bool") => "1"
				}
			case arrayType: ArrayType =>
				fieldLength(arrayType.primitive) + " * " + arrayLength(arrayType)
		}
	}

	private def declareFunction(function: Function) = {
		s"def ${function.name}(" + function.params.map(_.name).mkString(", ") + ")"
	}

	private def generateMain() = {
		val builder = new StringBuilder
		builder ++= s"""#!/usr/bin/python
# Auto-generated by libinteractive

import array
import struct
import sys

${generateMessageLoop(
	idl.interfaces.map{
		interface => (interface, idl.main, pipeName(interface))
	},
	pipeName(idl.main)
)}

if __name__ == '__main__':
"""
		var indentLevel = 1
		idl.interfaces.foreach(interface => {
			if (options.verbose) {
				builder ++= "\t" * indentLevel + "print>>sys.stderr," +
						s""" "\\t[${idl.main.name}] opening `${pipeFilename(interface)}'"\n"""
			}
			builder ++= "\t" * indentLevel +
					s"""with open("${pipeFilename(interface)}", 'wb') as ${pipeName(interface)}:\n"""
			indentLevel += 1
		})
		if (options.verbose) {
			builder ++= "\t" * indentLevel + "print>>sys.stderr," +
					s""" "\\t[${idl.main.name}] opening `${pipeFilename(idl.main)}'"\n"""
		}
		builder ++= "\t" * indentLevel +
				s"""with open("${pipeFilename(idl.main)}", 'rb') as ${pipeName(idl.main)}:\n"""
		indentLevel += 1
		builder ++= "\t" * indentLevel + s"import ${idl.main.name}_lib\n"
		idl.interfaces.foreach(interface =>
			builder ++= "\t" * indentLevel + s"${idl.main.name}_lib.${pipeName(interface).substring(2)} = ${pipeName(interface)}\n"
		)
		builder ++= "\t" * indentLevel + s"${idl.main.name}_lib.${pipeName(idl.main).substring(2)} = ${pipeName(idl.main)}\n"
		builder ++= "\t" * indentLevel + s"${idl.main.name}_lib.message_loop = __message_loop\n"
		builder ++= "\t" * indentLevel + s"import ${idl.main.name}\n"

		OutputFile(s"${idl.main.name}_entry.py", builder.mkString)
	}

	private def generateMainLib() = {
		val builder = new StringBuilder
		builder ++= s"""#!/usr/bin/python
# Auto-generated by libinteractive

import struct
import sys
import time

${idl.interfaces.map(interface =>
	pipeName(interface).substring(2) + " = None"
).mkString("\n")}
${pipeName(idl.main).substring(2)} = None
message_loop = None
elapsed_time = 0

"""
		idl.interfaces.foreach(interface => {
			interface.functions.foreach(
				builder ++= generateShim(_, interface, idl.main,
					pipeName(interface).substring(2), pipeName(idl.main).substring(2), true)
			)
		})
		OutputFile(s"${idl.main.name}_lib.py", builder.mkString)
	}

	private def generateLib(interface: Interface) = {
		val builder = new StringBuilder
		builder ++= s"""#!/usr/bin/python
# Auto-generated by libinteractive

import struct
import sys

fin = None
fout = None
message_loop = None

"""
		for (function <- idl.main.functions) {
			builder ++= generateShim(function, idl.main, interface, "fout", "fin", false)
		}
		OutputFile(s"${interface.name}_lib.py", builder.mkString)
	}

	private def generate(interface: Interface) = {
		val builder = new StringBuilder
		builder ++= s"""#!/usr/bin/python
# Auto-generated by libinteractive

import array
import struct
import sys

${generateMessageLoop(List((idl.main, interface, "__fout")), "__fin")}

if __name__ == '__main__':
	${if (options.verbose) {
		"print>>sys.stderr, \"\\t[" + interface.name + "] opening `" + pipeFilename(interface) + "'\""
	} else ""}
	with open("${pipeFilename(interface)}", 'rb') as __fin:
	${if (options.verbose) {
		"\tprint>>sys.stderr, \"\\t[" + interface.name + "] opening `" + pipeFilename(idl.main) + "'\""
	} else ""}
		with open("${pipeFilename(idl.main)}", 'wb') as __fout:
			import ${interface.name}_lib
			${interface.name}_lib.fin = __fin
			${interface.name}_lib.fout = __fout
			${interface.name}_lib.message_loop = __message_loop
			from ${options.moduleName} import ${interface.functions.map(_.name).mkString(", ")}
			__message_loop(-1)
"""
		OutputFile(s"${interface.name}_entry.py", builder.mkString)
	}

	private def generateMessageLoop(interfaces: List[(Interface, Interface, String)], infd: String) = {
		val builder = new StringBuilder
		builder ++= s"""def __message_loop(current_function):
	global $infd, ${interfaces.map(_._3).mkString(", ")}
	while True:
		buf = $infd.read(4)
		if len(buf) == 0:
			break
		elif len(buf) != 4:
			print>>sys.stderr, "Incomplete message"
			sys.exit(1)
		msgid = struct.unpack('I', buf)[0]
		if msgid == current_function:
			return\n"""
		for ((caller, callee, outfd) <- interfaces) {
			for (function <- callee.functions) {
				builder ++= f"\t\telif msgid == 0x${functionIds((caller.name, callee.name, function.name))}%x:\n"
				builder ++= s"\t\t\t# ${caller.name} -> ${callee.name}.${function.name}\n"
				if (options.verbose) {
					builder ++=
						s"""\t\t\tprint>>sys.stderr, "\\t[${callee.name}] calling ${function.name} begin"\n"""
				}
				for (param <- function.params) {
					builder ++= (param.paramType match {
						case array: ArrayType => {
							s"\t\t\t${param.name} = array.array(${structFormat(array.primitive)})\n" +
							s"\t\t\t${param.name}.fromstring($infd.read(${fieldLength(array)}))\n"
						}
						case primitive: PrimitiveType => {
							s"\t\t\t${param.name} = struct.unpack(${structFormat(primitive)}, " +
									s"$infd.read(${fieldLength(primitive)}))[0]\n"
						}
					})
				}
				builder ++= s"\t\t\tcookie = struct.unpack('I', $infd.read(4))[0]\n"
				builder ++= (if (function.returnType == PrimitiveType("void")) {
					"\t\t\t"
				} else {
					s"\t\t\tresult = "
				})
				builder ++=
					s"""${function.name}(${function.params.map(_.name).mkString(", ")});\n"""
				builder ++= s"\t\t\t$outfd.write(struct.pack('I', msgid))\n"
				if (function.returnType != PrimitiveType("void")) {
					builder ++= s"\t\t\t$outfd.write(struct.pack(" +
							s"${structFormat(function.returnType)}, result))\n"
				}
				builder ++= s"\t\t\t$outfd.write(struct.pack('I', cookie))\n"
				builder ++= s"\t\t\t$outfd.flush()\n"
				if (options.verbose) {
					builder ++=
						s"""\t\t\tprint>>sys.stderr, "\\t[${callee.name}] calling ${function.name} end"\n"""
				}
			}
		}
		builder ++= """		else:
			print>>sys.stderr, "Unknown message id 0x%x" % msgid
			sys.exit(1)
	if current_function != -1:
		print>>sys.stderr, "Confused about exiting"
		sys.exit(1)
"""
		builder
	}

	private def generateShim(function: Function, callee: Interface, caller: Interface,
			outfd: String, infd: String, generateTiming: Boolean) = {
		val builder = new StringBuilder
		builder ++= declareFunction(function)
		builder ++= ":\n"
		if (options.verbose) {
			builder ++=
				s"""\tprint>>sys.stderr, "\\t[${caller.name}] invoking ${function.name} begin\"\n"""
		}
		builder ++= f"\tmsgid = 0x${functionIds((caller.name, callee.name, function.name))}%x\n"
		builder ++= f"\tcookie = 0x${rand.nextInt}%x\n"
		builder ++= s"\t$outfd.write(struct.pack('I', msgid))\n"
		function.params.foreach(param => {
			builder ++=
				s"\t$outfd.write(struct.pack(${structFormat(param.paramType)}, " +
					s"${param.name}))\n"
		})
		if (generateTiming) {
			builder ++=
				"\tt0 = time.time()\n"
		}
		builder ++= s"\t$outfd.write(struct.pack('I', cookie))\n"
		builder ++= s"\t$outfd.flush()\n"
		builder ++= "\tmessage_loop(msgid)\n"
		if (function.returnType != PrimitiveType("void")) {
			builder ++= s"\tans = struct.unpack(${structFormat(function.returnType)}, " +
					s"$infd.read(${fieldLength(function.returnType)}))[0]\n"
		}
		builder ++= s"\tcookie_result = struct.unpack('I', $infd.read(4))[0]\n"
		if (generateTiming) {
			builder ++= "\tt1 = time.time()\n"
			builder ++= "\tglobal elapsed_time\n"
			builder ++= "\telapsed_time += int((t1 - t0) * 1e9)\n"
		}

		builder ++= "\tif cookie != cookie_result:\n"
		builder ++= "\t\tprint>>sys.stderr, \"invalid cookie\"\n"
		builder ++= "\t\tsys.exit(1)\n"

		if (options.verbose) {
			builder ++=
				s"""\tprint>>sys.stderr, "\\t[${caller.name}] invoking ${function.name} end"\n"""
		}

		if (function.returnType != PrimitiveType("void")) {
			builder ++= "\treturn ans\n"
		}

		builder
	}
}

/* vim: set noexpandtab: */
