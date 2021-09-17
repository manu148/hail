package is.hail.types.physical.stypes.interfaces

import is.hail.annotations.Region
import is.hail.asm4s._
import is.hail.expr.ir.{EmitCodeBuilder, IEmitCode}
import is.hail.types.physical.PCanonicalArray
import is.hail.types.physical.stypes.primitives.SInt32Code
import is.hail.types.physical.stypes.{EmitType, SCode, SType, SValue}
import is.hail.types.{RIterable, TypeWithRequiredness}

trait SContainer extends SType {
  def elementType: SType
  def elementEmitType: EmitType
  override def _typeWithRequiredness: TypeWithRequiredness = RIterable(elementEmitType.typeWithRequiredness.r)
}

trait SIndexableValue extends SValue {
  def st: SContainer

  override def get: SIndexableCode

  def loadLength(): Value[Int]

  def isElementMissing(i: Code[Int]): Code[Boolean]

  def isElementDefined(i: Code[Int]): Code[Boolean] = !isElementMissing(i)

  def loadElement(cb: EmitCodeBuilder, i: Code[Int]): IEmitCode

  def hasMissingValues(cb: EmitCodeBuilder): Code[Boolean]

  def castToArray(cb: EmitCodeBuilder): SIndexableValue

  def forEachDefined(cb: EmitCodeBuilder)(f: (EmitCodeBuilder, Value[Int], SCode) => Unit): Unit = {
    val length = loadLength()
    val idx = cb.newLocal[Int]("foreach_idx", 0)
    cb.whileLoop(idx < length, {

      loadElement(cb, idx).consume(cb,
        {}, /*do nothing if missing*/
        { eltCode =>
          f(cb, idx, eltCode.get)
        })
      cb.assign(idx, idx + 1)
    })
  }

  def forEachDefinedOrMissing(cb: EmitCodeBuilder)(missingF: (EmitCodeBuilder, Value[Int]) => Unit, presentF: (EmitCodeBuilder, Value[Int], SCode) => Unit): Unit = {
    val length = loadLength()
    val idx = cb.newLocal[Int]("foreach_idx", 0)
    cb.whileLoop(idx < length, {

      loadElement(cb, idx).consume(cb,
        { /*do function if missing*/
          missingF(cb, idx)
        },
        { eltCode =>
          presentF(cb, idx, eltCode.get)
        })
      cb.assign(idx, idx + 1)
    })
  }

  override def hash(cb: EmitCodeBuilder): SInt32Code = {
    val hash_result = cb.newLocal[Int]("array_hash", 1)
    forEachDefinedOrMissing(cb)({ case (cb, idx) => cb.assign(hash_result, hash_result * 31) },
      { case (cb, idx, element) => cb.assign(hash_result, hash_result * 31 + element.memoize(cb, "array_hash_element").hash(cb).intCode(cb))
      })
    new SInt32Code(hash_result)
  }

  def sliceArray(cb: EmitCodeBuilder, region: Value[Region], pt: PCanonicalArray, start: Code[Int], end: Code[Int], deepCopy: Boolean = false): SIndexableCode = {
    val startMemo = cb.newLocal[Int]("sindexable_slice_array_start_memo", start)
    pt.constructFromElements(cb, region, cb.newLocal[Int]("slice_length", end - startMemo), deepCopy){ (cb, idx) =>
      this.loadElement(cb, idx + startMemo)
    }.get
  }
}
trait SIndexableCode extends SCode {
  def st: SContainer

  def codeLoadLength(): Code[Int]

  def memoize(cb: EmitCodeBuilder, name: String): SIndexableValue

  def memoizeField(cb: EmitCodeBuilder, name: String): SIndexableValue

  def castToArray(cb: EmitCodeBuilder): SIndexableCode
}

