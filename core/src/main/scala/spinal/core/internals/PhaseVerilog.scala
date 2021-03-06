/*                                                                           *\
**        _____ ____  _____   _____    __                                    **
**       / ___// __ \/  _/ | / /   |  / /   HDL Core                         **
**       \__ \/ /_/ // //  |/ / /| | / /    (c) Dolu, All rights reserved    **
**      ___/ / ____// // /|  / ___ |/ /___                                   **
**     /____/_/   /___/_/ |_/_/  |_/_____/                                   **
**                                                                           **
**      This library is free software; you can redistribute it and/or        **
**    modify it under the terms of the GNU Lesser General Public             **
**    License as published by the Free Software Foundation; either           **
**    version 3.0 of the License, or (at your option) any later version.     **
**                                                                           **
**      This library is distributed in the hope that it will be useful,      **
**    but WITHOUT ANY WARRANTY; without even the implied warranty of         **
**    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU      **
**    Lesser General Public License for more details.                        **
**                                                                           **
**      You should have received a copy of the GNU Lesser General Public     **
**    License along with this library.                                       **
\*                                                                           */
package spinal.core.internals

import spinal.core._

import scala.collection.mutable


class PhaseVerilog(pc: PhaseContext, report: SpinalReport[_]) extends PhaseMisc with VerilogBase {
  import pc._

  var outFile: java.io.FileWriter = null

  override def impl(pc: PhaseContext): Unit = {
    val targetPath = pc.config.targetDirectory + "/" +  (if(pc.config.netlistFileName == null)(topLevel.definitionName + ".v") else pc.config.netlistFileName)
    report.generatedSourcesPaths += targetPath
    report.toplevelName = pc.topLevel.definitionName
    outFile = new java.io.FileWriter(targetPath)
    outFile.write(VhdlVerilogBase.getHeader("//",topLevel))

    if(pc.config.dumpWave != null) {
      outFile.write("`timescale 1ns/1ps ")
    }

    emitEnumPackage(outFile)

    for (c <- sortedComponents) {
      if (!c.isInBlackBoxTree) {
        SpinalProgress(s"${"  " * (1 + c.level)}emit ${c.definitionName}")
        compile(c)
      }
    }

    outFile.flush()
    outFile.close()
  }

  val allocateAlgoIncrementaleBase = globalData.allocateAlgoIncrementale()

  def compile(component: Component): Unit = {
    val componentBuilderVerilog = new ComponentEmiterVerilog(component, this, allocateAlgoIncrementaleBase, config.mergeAsyncProcess, config.asyncResetCombSensitivity, globalData.anonymSignalPrefix, emitedComponentRef)

    if(component.parentScope == null && pc.config.dumpWave != null) {
      componentBuilderVerilog.logics ++=
        s"""
  initial begin
    $$dumpfile("${pc.config.dumpWave.vcdPath}");
    $$dumpvars(${pc.config.dumpWave.depth}, ${component.definitionName});
  end
"""
    }

    val trace = componentBuilderVerilog.getTrace()
    val oldComponent = emitedComponent.getOrElse(trace, null)

    val text = if (oldComponent == null) {
      emitedComponent += (trace -> component)
      componentBuilderVerilog.result
    } else {
      emitedComponentRef.put(component, oldComponent)
      val str =  s"\n//${component.definitionName} remplaced by ${oldComponent.definitionName}\n\n"
      component.definitionName = oldComponent.definitionName
      str
    }

    outFile.write(text)
  }

  val emitedComponent    = mutable.Map[ComponentEmiterTrace, Component]()
  val emitedComponentRef = new java.util.concurrent.ConcurrentHashMap[Component,Component]()

  def emitEnumPackage(out: java.io.FileWriter): Unit = {
    val ret = new StringBuilder()

    ret ++= "\n"
    for ((enumDef, encodings) <- enums) {
      val enumName = enumDef.getName()
      for (encoding <- encodings) {
        val encodingName = encoding.getName()
        val bitCount     = encoding.getWidth(enumDef)
        val vhdlEnumType = emitEnumType(enumDef, encoding, "")

        ret ++= s"`define $vhdlEnumType [${bitCount - 1}:0]\n"

        for (element <- enumDef.elements) {
          ret ++= s"`define ${emitEnumLiteral(element, encoding,"")} ${idToBits(element, encoding)}\n"
        }

        ret ++= "\n"
      }
    }

    def idToBits[T <: SpinalEnum](enum: SpinalEnumElement[T], encoding: SpinalEnumEncoding): String = {
      val str    = encoding.getValue(enum).toString(2)
      val length = encoding.getWidth(enum.spinalEnum)
      length.toString + "'b" + ("0" * (length - str.length)) + str
    }

    out.write(ret.result())
  }


  def emitFunctions(component: Component, ret: StringBuilder): Unit = {
    val alreadyEmitted = mutable.Set[String]()
    component.dslBody.walkStatements(s => s.walkExpression {
      case node: CastEnumToEnum =>
        val encodingSrc = node.input.getEncoding
        val enumDef     = node.getDefinition
        val encodingDst = node.getEncoding
        val fName       = getReEncodingFuntion(enumDef, encodingSrc, encodingDst)

        if (!alreadyEmitted.contains(fName)) {
          alreadyEmitted += fName
          ret ++= s"  function $fName(${emitEnumType(enumDef, encodingSrc)} that);\n"
          ret ++= "  begin\n"
          ret ++= "    case(that) \n"
          for (e <- enumDef.elements) {
            ret ++= s"      ${emitEnumLiteral(e, encodingSrc)} : $fName =  ${emitEnumLiteral(e, encodingDst)};\n"
          }
          ret ++= s"      default : $fName =  ${emitEnumLiteral(enumDef.elements.head, encodingDst)};\n"
          ret ++= "    endcase\n"
          ret ++= "  end\n"
          ret ++= "  endfunction\n\n"
        }
      case _ =>
    })
  }
}
