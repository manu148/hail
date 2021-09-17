package is.hail.types.physical.stypes.concrete

import is.hail.annotations.Region
import is.hail.asm4s.{Code, Settable, TypeInfo, Value}
import is.hail.expr.ir.{EmitCode, EmitCodeBuilder, EmitSettable, EmitValue, IEmitCode}
import is.hail.types.physical.stypes.interfaces.{SBaseStruct, SBaseStructCode, SBaseStructSettable, SBaseStructValue}
import is.hail.types.physical.stypes.{EmitType, SCode, SType, SValue}
import is.hail.types.physical.{PCanonicalBaseStruct, PCanonicalStruct, PCanonicalTuple, PTupleField, PType}
import is.hail.types.virtual.{TBaseStruct, TStruct, TTuple, Type}

object SStackStruct {
  val MAX_FIELDS_FOR_CONSTRUCT: Int = 64

  def constructFromArgs(cb: EmitCodeBuilder, region: Value[Region], t: TBaseStruct, args: EmitCode*): SBaseStructValue = {
    val as = args.toArray
    assert(t.size == args.size)
    if (as.length > MAX_FIELDS_FOR_CONSTRUCT) {
      val structType: PCanonicalBaseStruct = t match {
        case ts: TStruct =>
          PCanonicalStruct(false, ts.fieldNames.zip(as.map(_.emitType)).map { case (f, et) => (f, et.storageType) }: _*)
        case tt: TTuple =>
          PCanonicalTuple(tt._types.zip(as.map(_.emitType)).map { case (tf, et) => PTupleField(tf.index, et.storageType) }, false)
      }
      structType.constructFromFields(cb, region, as, false)
    } else {
      val st = SStackStruct(t, as.map(_.emitType))
      new SStackStructCode(st, as).memoize(cb, "SStackStruct_constructFromArgs")
    }
  }
}

final case class SStackStruct(virtualType: TBaseStruct, fieldEmitTypes: IndexedSeq[EmitType]) extends SBaseStruct {
  override def size: Int = virtualType.size

  private lazy val settableStarts = fieldEmitTypes.map(_.nSettables).scanLeft(0)(_ + _).init

  override lazy val fieldTypes: IndexedSeq[SType] = fieldEmitTypes.map(_.st)

  override def fieldIdx(fieldName: String): Int = virtualType.fieldIdx(fieldName)

  override def storageType(): PType = virtualType match {
    case ts: TStruct =>
      PCanonicalStruct(false, ts.fieldNames.zip(fieldEmitTypes).map { case (f, et) => (f, et.storageType) }: _*)
    case tt: TTuple =>
      PCanonicalTuple(tt._types.zip(fieldEmitTypes).map { case (tf, et) => PTupleField(tf.index, et.storageType) }, false)
  }

  override def copiedType: SType = SStackStruct(virtualType, fieldEmitTypes.map(f => f.copy(st = f.st.copiedType)))

  override def containsPointers: Boolean = fieldEmitTypes.exists(_.st.containsPointers)

  override lazy val settableTupleTypes: IndexedSeq[TypeInfo[_]] = fieldEmitTypes.flatMap(_.settableTupleTypes)

  override def fromSettables(settables: IndexedSeq[Settable[_]]): SStackStructSettable = {
    assert(settables.length == fieldEmitTypes.map(_.nSettables).sum, s"mismatch: ${ settables.length } settables, expect ${ fieldEmitTypes.map(_.nSettables).sum }\n  ${ settables.map(_.ti).mkString(",") }\n  ${ fieldEmitTypes.map(_.settableTupleTypes).mkString(" | ") }")
    new SStackStructSettable(this, fieldEmitTypes.indices.map { i =>
      val et = fieldEmitTypes(i)
      val start = settableStarts(i)
      et.fromSettables(settables.slice(start, start + et.nSettables))
    })
  }

  override def fromValues(values: IndexedSeq[Value[_]]): SStackStructValue = {
    assert(values.length == fieldEmitTypes.map(_.nSettables).sum, s"mismatch: ${ values.length } settables, expect ${ fieldEmitTypes.map(_.nSettables).sum }\n  ${ values.map(_.ti).mkString(",") }\n  ${ fieldEmitTypes.map(_.settableTupleTypes).mkString(" | ") }")
    new SStackStructValue(this, fieldEmitTypes.indices.map { i =>
      val et = fieldEmitTypes(i)
      val start = settableStarts(i)
      et.fromValues(values.slice(start, start + et.nSettables))
    })
  }

  override def _coerceOrCopy(cb: EmitCodeBuilder, region: Value[Region], value: SValue, deepCopy: Boolean): SValue = {
    value match {
      case ss: SStackStructValue =>
        if (ss.st == this && !deepCopy)
          ss
        else
          new SStackStructValue(this, fieldEmitTypes.zip(ss.values).map { case (newType, ev) =>
            val iec = ev.map(cb) { field => newType.st.coerceOrCopy(cb, region, field, deepCopy) }
            (newType.required, iec.required) match {
              case (true, false) => EmitValue.present(iec.get(cb))
              case (false, true) => iec.setOptional
              case _ => iec
            }
          })
      case _ =>
        val sv = value.asBaseStruct
        new SStackStructValue(this, Array.tabulate[EmitValue](size) { i =>
          val newType = fieldEmitTypes(i)
          val ec = EmitCode.fromI(cb.emb) { cb =>
            sv.loadField(cb, i).map(cb) { field => newType.st.coerceOrCopy(cb, region, field, deepCopy) }
          }
          val ev = ec.memoize(cb, "_coerceOrCopy")
          (newType.required, ev.required) match {
            case (true, false) => EmitValue.present(ev.get(cb))
            case (false, true) => ev.setOptional
            case _ => ev
          }
        })
    }
  }

  override def castRename(t: Type): SType = {
    val ts = t.asInstanceOf[TBaseStruct]
    SStackStruct(
      ts,
      ts.types.zip(fieldEmitTypes).map { case (v, e) => e.copy(st = e.st.castRename(v)) }
    )
  }
}

class SStackStructValue(val st: SStackStruct, val values: IndexedSeq[EmitValue]) extends SBaseStructValue {
  override lazy val valueTuple: IndexedSeq[Value[_]] = values.flatMap(_.valueTuple)

  override def get: SStackStructCode = new SStackStructCode(st, values.map(_.load))

  override def loadField(cb: EmitCodeBuilder, fieldIdx: Int): IEmitCode = {
    values(fieldIdx).toI(cb)
  }

  override def isFieldMissing(fieldIdx: Int): Code[Boolean] =
    values(fieldIdx).m

  override def subset(fieldNames: String*): SStackStructValue = {
    val newToOld = fieldNames.map(st.fieldIdx).toArray
    val oldVType = st.virtualType.asInstanceOf[TStruct]
    val newVirtualType = TStruct(newToOld.map(i => (oldVType.fieldNames(i), oldVType.types(i))): _*)
    new SStackStructValue(SStackStruct(newVirtualType, newToOld.map(st.fieldEmitTypes)), newToOld.map(values))
  }
}

final class SStackStructSettable(
  st: SStackStruct,
  settables: IndexedSeq[EmitSettable]
) extends SStackStructValue(st, settables) with SBaseStructSettable {
  override def settableTuple(): IndexedSeq[Settable[_]] = settables.flatMap(_.settableTuple())

  override def store(cb: EmitCodeBuilder, pv: SCode): Unit = {
    val ssc = pv.asInstanceOf[SStackStructCode]
    settables.zip(ssc.codes).foreach { case (s, c) => s.store(cb, c) }
  }
}

class SStackStructCode(val st: SStackStruct, val codes: IndexedSeq[EmitCode]) extends SBaseStructCode {
  override def memoize(cb: EmitCodeBuilder, name: String): SStackStructSettable = {
    new SStackStructSettable(st, codes.indices.map { i =>
      val code = codes(i)
      val es = cb.emb.newEmitLocal(s"${ name }_$i", code.emitType)
      es.store(cb, code)
      es
    })
  }

  override def memoizeField(cb: EmitCodeBuilder, name: String): SStackStructSettable = {
    new SStackStructSettable(st, codes.indices.map { i =>
      val code = codes(i)
      val es = cb.emb.newEmitField(s"${ name }_$i", code.emitType)
      es.store(cb, code)
      es
    })
  }

  override def loadSingleField(cb: EmitCodeBuilder, fieldIdx: Int): IEmitCode = {
    codes(fieldIdx).toI(cb)
  }
}