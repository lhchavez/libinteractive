package com.omegaup.libinteractive.target

import scala.collection.mutable.StringBuilder

import com.omegaup.libinteractive.idl._

class Java(idl: IDL, options: Options) extends Target(idl, options) {
	override def generateMakefileRules() = ???
	override def generateRunCommands() = ???
	override def generate() = ???
	override def createWorkDirs() = ???
}

/* vim: set noexpandtab: */
