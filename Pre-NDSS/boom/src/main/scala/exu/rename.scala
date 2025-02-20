//******************************************************************************
// Copyright (c) 2015, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE for license details.
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// RISCV Processor Datapath: Rename Logic
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
//
// Christopher Celio
// 2012 Feb 14
//
// Supports 1-cycle and 2-cycle latencies. (aka, passthrough versus registers between ren1 and ren2).
//    - ren1: read the map tables and allocate a new physical register from the freelist.
//    - ren2: read the busy table for the physical operands.
//
// Ren1 data is provided as an output to be fed directly into the ROB.


package boom.exu

import Chisel._
import freechips.rocketchip.config.Parameters
import boom.common._
import boom.util._


class RenameStageIO(
   val pl_width: Int,
   val num_int_pregs: Int,
   val num_fp_pregs: Int,
   val num_int_wb_ports: Int,
   val num_fp_wb_ports: Int)
   (implicit p: Parameters) extends BoomBundle()(p)
{
   private val int_preg_sz = log2Up(num_int_pregs)
   private val fp_preg_sz = log2Up(num_fp_pregs)

   val inst_can_proceed = Vec(pl_width, Bool()).asOutput

   val kill      = Bool(INPUT)

   val dec_will_fire = Vec(pl_width, Bool()).asInput // will commit state updates
   val dec_uops  = Vec(pl_width, new MicroOp()).asInput

   // physical specifiers now available (but not the busy/ready status of the operands).
   val ren1_mask = Vec(pl_width, Bool().asOutput) // mask of valid instructions
   val ren1_uops = Vec(pl_width, new MicroOp().asOutput)

   // physical specifiers available AND busy/ready status available.
   val ren2_mask  = Vec(pl_width, Bool().asOutput) // mask of valid instructions
   val ren2_uops  = Vec(pl_width, new MicroOp().asOutput)

   // branch resolution (execute)
   val brinfo    = new BrResolutionInfo().asInput

   val dis_inst_can_proceed = Vec(DISPATCH_WIDTH, Bool()).asInput

   // issue stage (fast wakeup)
   val int_wakeups = Vec(num_int_wb_ports, Valid(new ExeUnitResp(xLen))).flip
   val fp_wakeups = Vec(num_fp_wb_ports, Valid(new ExeUnitResp(fLen+1))).flip

   // commit stage
   val com_valids = Vec(pl_width, Bool()).asInput
   val com_uops   = Vec(pl_width, new MicroOp()).asInput
   val com_rbk_valids = Vec(pl_width, Bool()).asInput

   val flush_pipeline = Bool(INPUT) // only used for SCR (single-cycle reset)

   val debug_rob_empty = Bool(INPUT)
   val debug = new DebugRenameStageIO(num_int_pregs, num_fp_pregs).asOutput
}


class DebugRenameStageIO(int_num_pregs: Int, fp_num_pregs: Int)(implicit p: Parameters) extends BoomBundle()(p)
{
   val ifreelist = Bits(width=int_num_pregs)
   val iisprlist = Bits(width=int_num_pregs)
   val ibusytable = UInt(width=int_num_pregs)
   val ffreelist = Bits(width=fp_num_pregs)
   val fisprlist = Bits(width=fp_num_pregs)
   val fbusytable = UInt(width=fp_num_pregs)
   override def cloneType: this.type = new DebugRenameStageIO(int_num_pregs, fp_num_pregs).asInstanceOf[this.type]
}


class RenameStage(
   pl_width: Int,
   num_int_wb_ports: Int,
   num_fp_wb_ports: Int)
(implicit p: Parameters) extends BoomModule()(p)
{
   val io = IO(new RenameStageIO(pl_width, numIntPhysRegs, numFpPhysRegs, num_int_wb_ports, num_fp_wb_ports))

   // integer registers
   val imaptable = Module(new RenameMapTable(
      pl_width,
      RT_FIX.litValue,
      32,     // There are fixed 32 logical integer registers(x0-x31)
      numIntPhysRegs))
   val ifreelist = Module(new RenameFreeList(
      pl_width,
      RT_FIX.litValue,
      numIntPhysRegs))
   val ibusytable = Module(new BusyTable(
      pl_width,
      RT_FIX.litValue,
      num_pregs = numIntPhysRegs,
      num_read_ports = pl_width*3,
      num_wb_ports = num_int_wb_ports))

   // floating point registers
   val fmaptable = Module(new RenameMapTable(
      pl_width,
      RT_FLT.litValue,
      32,     // There are fixed 32 logical floating point registers(x0-x31)
      numFpPhysRegs))
   val ffreelist = Module(new RenameFreeList(
      pl_width,
      RT_FLT.litValue,
      numFpPhysRegs))
   val fbusytable = Module(new BusyTable(
      pl_width,
      RT_FLT.litValue,
      num_pregs = numFpPhysRegs,
      num_read_ports = pl_width*3,
      num_wb_ports = num_fp_wb_ports))

   //-------------------------------------------------------------
   // Pipeline State & Wires

   // physical specifiers now available (but not the busy/ready status of the operands).
   // - ren1: read the map tables and allocate a new physical register from the freelist.
   val ren1_br_vals   = Wire(Vec(pl_width, Bool()))
   val ren1_will_fire = Wire(Vec(pl_width, Bool()))
   val ren1_uops      = Wire(Vec(pl_width, new MicroOp()))

   // physical specifiers available AND busy/ready status available.
   // - ren2: read the busy table for the physical operands.
   val ren2_valids    = Wire(Vec(pl_width, Bool()))
   val ren2_uops      = Wire(Vec(pl_width, new MicroOp()))

   for (w <- 0 until pl_width)
   {
      // TODO silly, we've already verified this beforehand on the inst_can_proceed
      ren1_will_fire(w) := io.dec_will_fire(w) && io.inst_can_proceed(w) && !io.kill
      ren1_uops(w)      := GetNewUopAndBrMask(io.dec_uops(w), io.brinfo)
      ren1_br_vals(w)   := io.dec_will_fire(w) && io.dec_uops(w).allocate_brtag
   }

   //-------------------------------------------------------------
   // Free List

   for (list <- Seq(ifreelist, ffreelist))
   {
      list.io.brinfo := io.brinfo
      list.io.kill := io.kill
      list.io.ren_will_fire := ren1_will_fire
      list.io.ren_uops := ren1_uops
      list.io.ren_br_vals := ren1_br_vals
      list.io.com_valids := io.com_valids
      list.io.com_uops := io.com_uops
      list.io.com_rbk_valids := io.com_rbk_valids
      list.io.flush_pipeline := io.flush_pipeline
      list.io.debug_rob_empty := io.debug_rob_empty
   }

   // Here we must fetch a new register to rename our destination logical register
   // Really? Two exceptions: store instruction. instruction with RD=RS1 or RS2
   for ((uop, w) <- ren1_uops.zipWithIndex)
   {
      val i_preg = ifreelist.io.req_pregs(w)
      val f_preg = ffreelist.io.req_pregs(w)
      uop.pdst := Mux(uop.dst_rtype === RT_FLT, f_preg, i_preg)
   }

   //-------------------------------------------------------------
   // Rename Table

   for (table <- Seq(imaptable, fmaptable))
   {
      table.io.brinfo := io.brinfo
      table.io.kill := io.kill
      table.io.ren_will_fire := ren1_will_fire
      table.io.ren_uops := ren1_uops // expects pdst to be set up
      table.io.ren_br_vals := ren1_br_vals
      table.io.com_valids := io.com_valids
      table.io.com_uops := io.com_uops
      table.io.com_rbk_valids := io.com_rbk_valids
      table.io.flush_pipeline := io.flush_pipeline
      table.io.debug_inst_can_proceed := io.inst_can_proceed
   }
   imaptable.io.debug_freelist_can_allocate := ifreelist.io.can_allocate
   fmaptable.io.debug_freelist_can_allocate := ffreelist.io.can_allocate

   for ((uop, w) <- ren1_uops.zipWithIndex)
   {
      val imap = imaptable.io.values(w)
      val fmap = fmaptable.io.values(w)

      uop.pop1       := Mux(uop.lrs1_rtype === RT_FLT, fmap.prs1, imap.prs1)  // the physical register mapped to RS1
      uop.pop2       := Mux(uop.lrs2_rtype === RT_FLT, fmap.prs2, imap.prs2)  // the physical register mapped to RS2
      uop.pop3       := fmaptable.io.values(w).prs3 // only FP has 3rd operand
      uop.stale_pdst := Mux(uop.dst_rtype === RT_FLT,  fmap.stale_pdst, imap.stale_pdst)  // the old physical register mapped to RD
   }

   //-------------------------------------------------------------
   // pipeline registers

   val ren2_will_fire = ren2_valids zip io.dis_inst_can_proceed map {case (v,c) => v && c && !io.kill}

   // will ALL ren2 uops proceed to dispatch?
   val ren2_will_proceed =
      if (renameLatency == 2) (ren2_valids zip ren2_will_fire map {case (v,f) => (v === f)}).reduce(_&_)
      else io.dis_inst_can_proceed.reduce(_&_)


   // serve as pipeline register after rename stage
   val ren2_imapvalues = if (renameLatency == 2) RegEnable(imaptable.io.values, ren2_will_proceed)
                         else imaptable.io.values
   val ren2_fmapvalues = if (renameLatency == 2) RegEnable(fmaptable.io.values, ren2_will_proceed)
                         else fmaptable.io.values

   for (w <- 0 until pl_width)
   {
      if (renameLatency == 1)
      {
         ren2_valids(w) := ren1_will_fire(w)
         ren2_uops(w)   := GetNewUopAndBrMask(ren1_uops(w), io.brinfo)
      }
      else
      {
         require (renameLatency == 2)
         val r_valid = Reg(init = false.B)
         val r_uop   = Reg(new MicroOp())

         when (io.kill)
         {
            r_valid := false.B
         }
         .elsewhen (ren2_will_proceed)
         {
            r_valid := ren1_will_fire(w)
            r_uop := GetNewUopAndBrMask(ren1_uops(w), io.brinfo)
         }
         .otherwise
         {
            r_valid := r_valid && !ren2_will_fire(w) // clear bit if uop gets dispatched
            r_uop := GetNewUopAndBrMask(r_uop, io.brinfo)
         }

         ren2_valids(w) := r_valid
         ren2_uops  (w) := r_uop
      }
   }

   //-------------------------------------------------------------
   // Busy Table

   ibusytable.io.ren_will_fire := ren2_will_fire
   ibusytable.io.ren_uops := ren2_uops  // expects pdst to be set up.
   ibusytable.io.map_table := ren2_imapvalues
   ibusytable.io.wb_valids := io.int_wakeups.map(_.valid)
   ibusytable.io.wb_pdsts := io.int_wakeups.map(_.bits.uop.pdst)

   assert (!(io.int_wakeups.map(x => x.valid && x.bits.uop.dst_rtype =/= RT_FIX).reduce(_|_)),
      "[rename] int wakeup is not waking up a Int register.")

   for (w <- 0 until pl_width)
   {
      assert (!(
         ren2_will_fire(w) &&
         ren2_uops(w).lrs1_rtype === RT_FIX &&
         ren2_uops(w).pop1 =/= ibusytable.io.map_table(w).prs1),
         "[rename] ren2 maptable prs1 value don't match uop's values.")
      assert (!(
         ren2_will_fire(w) &&
         ren2_uops(w).lrs2_rtype === RT_FIX &&
         ren2_uops(w).pop2 =/= ibusytable.io.map_table(w).prs2),
         "[rename] ren2 maptable prs2 value don't match uop's values.")
      assert (!(
         ren2_will_fire(w) &&
         ren2_uops(w).dst_rtype  === RT_FIX &&
         ren2_uops(w).stale_pdst =/= ibusytable.io.map_table(w).stale_pdst),
         "[rename] ren2 maptable stale_pdst value don't match uop's values.")
   }

   fbusytable.io.ren_will_fire := ren2_will_fire
   fbusytable.io.ren_uops := ren2_uops  // expects pdst to be set up.
   fbusytable.io.map_table := ren2_fmapvalues
   fbusytable.io.wb_valids := io.fp_wakeups.map(_.valid)
   fbusytable.io.wb_pdsts := io.fp_wakeups.map(_.bits.uop.pdst)

   assert (!(io.fp_wakeups.map(x => x.valid && x.bits.uop.dst_rtype =/= RT_FLT).reduce(_|_)),
      "[rename] fp wakeup is not waking up a FP register.")

   for ((uop, w) <- ren2_uops.zipWithIndex)
   {
      val ibusy = ibusytable.io.values(w)
      val fbusy = fbusytable.io.values(w)
      uop.prs1_busy := Mux(uop.lrs1_rtype === RT_FLT, fbusy.prs1_busy, ibusy.prs1_busy)
      uop.prs2_busy := Mux(uop.lrs2_rtype === RT_FLT, fbusy.prs2_busy, ibusy.prs2_busy)
      uop.prs3_busy := fbusy.prs3_busy
      uop.pdst_busy := ibusy.pdst_busy

      val valid = ren2_valids(w)
      assert (!(valid && ibusy.prs1_busy && uop.lrs1_rtype === RT_FIX && uop.lrs1 === UInt(0)), "[rename] x0 is busy??")
      assert (!(valid && ibusy.prs2_busy && uop.lrs2_rtype === RT_FIX && uop.lrs2 === UInt(0)), "[rename] x0 is busy??")
      assert (!(valid && ibusy.pdst_busy && uop.dst_rtype  === RT_FIX && uop.ldst === UInt(0)), "[rename] x0 is busy??")
   }

   //-------------------------------------------------------------
   // Outputs

   io.ren1_mask := ren1_will_fire
   io.ren1_uops := ren1_uops

   io.ren2_mask := ren2_will_fire
   io.ren2_uops := ren2_uops map {u => GetNewUopAndBrMask(u, io.brinfo)}

   for (w <- 0 until pl_width)
   {
      // Push back against Decode stage if Rename1 can't proceed (and Rename2/Dispatch can't receive).
      io.inst_can_proceed(w) :=
         ren2_will_proceed &&
         ((ren1_uops(w).dst_rtype =/= RT_FIX && ren1_uops(w).dst_rtype =/= RT_FLT) ||
         (ifreelist.io.can_allocate(w) && ren1_uops(w).dst_rtype === RT_FIX) ||
         (ffreelist.io.can_allocate(w) && ren1_uops(w).dst_rtype === RT_FLT))
   }


   //-------------------------------------------------------------
   // Debug signals

   io.debug.ifreelist  := ifreelist.io.debug.freelist
   io.debug.iisprlist  := ifreelist.io.debug.isprlist
   io.debug.ibusytable := ibusytable.io.debug.busytable
   io.debug.ffreelist  := ffreelist.io.debug.freelist
   io.debug.fisprlist  := ffreelist.io.debug.isprlist
   io.debug.fbusytable := fbusytable.io.debug.busytable

   override val compileOptions = chisel3.core.ExplicitCompileOptions.NotStrict.copy(explicitInvalidate = true)
}

