// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package freechips.rocketchip.rocket

import Chisel._
import Chisel.ImplicitConversions._

import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.subsystem.CacheBlockBytes
import freechips.rocketchip.diplomacy.RegionType
import freechips.rocketchip.tile.{CoreModule, CoreBundle}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.util.property._
import freechips.rocketchip.devices.debug.DebugModuleKey
import chisel3.internal.sourceinfo.SourceInfo

case object PgLevels extends Field[Int](2)
case object ASIdBits extends Field[Int](0)

class SFenceReq(implicit p: Parameters) extends CoreBundle()(p) {
  val rs1 = Bool()
  val rs2 = Bool()
  val addr = UInt(width = vaddrBits)
  val asid = UInt(width = asIdBits max 1) // TODO zero-width
}

/** TLB request from cpu
  * vaddr: virtual address
  * passthrough: 
  * size:
  * cmd:
  *
  * @param lgMaxSize
  * @param p
  */
class TLBReq(lgMaxSize: Int)(implicit p: Parameters) extends CoreBundle()(p) {
  val vaddr = UInt(width = vaddrBitsExtended)
  val passthrough = Bool()
  val size = UInt(width = log2Ceil(lgMaxSize + 1))
  val cmd  = Bits(width = M_SZ)

  override def cloneType = new TLBReq(lgMaxSize).asInstanceOf[this.type]
}

/** TLB Exceptions
  * ld: load
  * st: store
  * inst: instruction
  */
class TLBExceptions extends Bundle {
  val ld = Bool()
  val st = Bool()
  val inst = Bool()
}

/** TLB Response to CPU
  * miss: tlb miss
  * paddr: physical address
  * pf: page fault
  * ae: access error
  * ma: misaligned address
  * cacheable:
  * must_alloc:
  * prefetchable:
  * 
  * @param p
  */
class TLBResp(implicit p: Parameters) extends CoreBundle()(p) {
  // lookup responses
  val miss = Bool()
  val paddr = UInt(width = paddrBits)
  val pf = new TLBExceptions
  val ae = new TLBExceptions
  val ma = new TLBExceptions
  val cacheable = Bool()
  val must_alloc = Bool()
  val prefetchable = Bool()
}

/** TLB Entry Data
  * ppn: physical pager number
  * u: user page
  * g: global page
  * ae: access error
  * sw/sx/sr: supervisor write/execute/read
  * pw/px/pr:
  * ppp: PutPartial
  * pal: AMO logical
  * paa: AMO arithmetic
  * eff: get/put effects
  * c:
  * fragmented_superpage:
  * 
  * @param p
  */
class TLBEntryData(implicit p: Parameters) extends CoreBundle()(p) {
  val ppn = UInt(width = ppnBits)
  val u = Bool()
  val g = Bool()
  val ae = Bool()
  val sw = Bool()
  val sx = Bool()
  val sr = Bool()
  val pw = Bool()
  val px = Bool()
  val pr = Bool()
  val ppp = Bool() // PutPartial
  val pal = Bool() // AMO logical
  val paa = Bool() // AMO arithmetic
  val eff = Bool() // get/put effects
  val c = Bool()
  val fragmented_superpage = Bool()
}

/** TLBEntry
  * level:
  * tag:
  * data:
  * valid:
  *
  * @param nSectors
  * @param superpage
  * @param superpageOnly
  * @param p
  */ 
class TLBEntry(val nSectors: Int, val superpage: Boolean, val superpageOnly: Boolean)(implicit p: Parameters) extends CoreBundle()(p) {
  require(nSectors == 1 || !superpage)
  require(!superpageOnly || superpage)

  val level = UInt(width = log2Ceil(pgLevels))
  val tag = UInt(width = vpnBits)
  val data = Vec(nSectors, UInt(width = new TLBEntryData().getWidth))
  val valid = Vec(nSectors, Bool())
  def entry_data = data.map(_.asTypeOf(new TLBEntryData))

  private def sectorIdx(vpn: UInt) = vpn.extract(nSectors.log2-1, 0)
  def getData(vpn: UInt) = OptimizationBarrier(data(sectorIdx(vpn)).asTypeOf(new TLBEntryData))
  def sectorHit(vpn: UInt) = valid.orR && sectorTagMatch(vpn)
  // match tag
  def sectorTagMatch(vpn: UInt) = ((tag ^ vpn) >> nSectors.log2) === 0
  // TLB hit
  def hit(vpn: UInt) = {
    if (superpage && usingVM) {
      var tagMatch = valid.head
      for (j <- 0 until pgLevels) {
        val base = vpnBits - (j + 1) * pgLevelBits
        val ignore = level < j || superpageOnly && j == pgLevels - 1
        tagMatch = tagMatch && (ignore || tag(base + pgLevelBits - 1, base) === vpn(base + pgLevelBits - 1, base))
      }
      tagMatch
    } else {
      val idx = sectorIdx(vpn)
      valid(idx) && sectorTagMatch(vpn)
    }
  }
  // get ppn by vpn from TLBEntryData
  def ppn(vpn: UInt, data: TLBEntryData) = {
    if (superpage && usingVM) {
      var res = data.ppn >> pgLevelBits*(pgLevels - 1)
      for (j <- 1 until pgLevels) {
        val ignore = level < j || superpageOnly && j == pgLevels - 1
        res = Cat(res, (Mux(ignore, vpn, 0.U) | data.ppn)(vpnBits - j*pgLevelBits - 1, vpnBits - (j + 1)*pgLevelBits))
      }
      res
    } else {
      data.ppn
    }
  }
  // insert new TLBEntry
  def insert(tag: UInt, level: UInt, entry: TLBEntryData): Unit = {
    this.tag := tag
    this.level := level.extract(log2Ceil(pgLevels - superpageOnly.toInt)-1, 0)

    val idx = sectorIdx(tag)
    valid(idx) := true
    data(idx) := entry.asUInt
  }
  // invalidate all tlb entries 
  def invalidate(): Unit = { valid.foreach(_ := false) }
  // invalidate tlb entry by vpn
  def invalidateVPN(vpn: UInt): Unit = {
    if (superpage) {
      when (hit(vpn)) { invalidate() }
    } else {
      when (sectorTagMatch(vpn)) { valid(sectorIdx(vpn)) := false }

      // For fragmented superpage mappings, we assume the worst (largest)
      // case, and zap entries whose most-significant VPNs match
      when (((tag ^ vpn) >> (pgLevelBits * (pgLevels - 1))) === 0) {
        for ((v, e) <- valid zip entry_data)
          when (e.fragmented_superpage) { v := false }
      }
    }
  }
  // invalidate nonglobal entries
  def invalidateNonGlobal(): Unit = {
    for ((v, e) <- valid zip entry_data)
      when (!e.g) { v := false }
  }
}

case class TLBConfig(
    nSets: Int,
    nWays: Int,
    nSectors: Int = 4,
    nSuperpageEntries: Int = 4)

/** TLB Module
  * io ports:
  *   req: request from cpu
  *   resp: response to cpu
  *   sfence:
  *   ptw: TLB PTW io
  *   kill:
  * 
  * @param instruction
  * @param lgMaxSize
  * @param cfg
  * @param edge
  * @param p
  */
class TLB(instruction: Boolean, lgMaxSize: Int, cfg: TLBConfig)(implicit edge: TLEdgeOut, p: Parameters) extends CoreModule()(p) {
  val io = new Bundle {
    val req = Decoupled(new TLBReq(lgMaxSize)).flip
    val resp = new TLBResp().asOutput
    val sfence = Valid(new SFenceReq).asInput
    val ptw = new TLBPTWIO
    val kill = Bool(INPUT) // suppress a TLB refill, one cycle after a miss
  }

  val pageGranularityPMPs = pmpGranularity >= (1 << pgIdxBits)
  val vpn = io.req.bits.vaddr(vaddrBits-1, pgIdxBits)
  // index bits
  val memIdx = vpn.extract(cfg.nSectors.log2 + cfg.nSets.log2 - 1, cfg.nSectors.log2)
  // allocate tlb entries
  val sectored_entries = Reg(Vec(cfg.nSets, Vec(cfg.nWays / cfg.nSectors, new TLBEntry(cfg.nSectors, false, false))))
  val superpage_entries = Reg(Vec(cfg.nSuperpageEntries, new TLBEntry(1, true, true)))
  val special_entry = (!pageGranularityPMPs).option(Reg(new TLBEntry(1, true, false)))
  def ordinary_entries = sectored_entries(memIdx) ++ superpage_entries
  def all_entries = ordinary_entries ++ special_entry
  def all_real_entries = sectored_entries.flatten ++ superpage_entries ++ special_entry

  //** state machine **//
  val s_ready :: s_request :: s_wait :: s_wait_invalidate :: Nil = Enum(UInt(), 4)
  val state = Reg(init=s_ready)
  //** registers **//
  val r_refill_tag = Reg(UInt(width = vpnBits))
  val r_superpage_repl_addr = Reg(UInt(log2Ceil(superpage_entries.size).W))
  val r_sectored_repl_addr = Reg(UInt(log2Ceil(sectored_entries(0).size).W))
  val r_sectored_hit_addr = Reg(UInt(log2Ceil(sectored_entries(0).size).W))
  val r_sectored_hit = Reg(Bool())

  //** instruction/data privilege **//
  val priv = if (instruction) io.ptw.status.prv else io.ptw.status.dprv
  val priv_s = priv(0)
  val priv_uses_vm = priv <= PRV.S
  //** virtual memory enabled **//
  val vm_enabled = Bool(usingVM) && io.ptw.ptbr.mode(io.ptw.ptbr.mode.getWidth-1) && priv_uses_vm && !io.req.bits.passthrough

  // share a single physical memory attribute checker (unshare if critical path)
  val refill_ppn = io.ptw.resp.bits.pte.ppn(ppnBits-1, 0)
  val do_refill = Bool(usingVM) && io.ptw.resp.valid
  val invalidate_refill = state.isOneOf(s_request /* don't care */, s_wait_invalidate) || io.sfence.valid
  // MPU physical page number/physical address/priviledge
  val mpu_ppn = Mux(do_refill, refill_ppn,
                Mux(vm_enabled && special_entry.nonEmpty, special_entry.map(e => e.ppn(vpn, e.getData(vpn))).getOrElse(0.U), io.req.bits.vaddr >> pgIdxBits))
  val mpu_physaddr = Cat(mpu_ppn, io.req.bits.vaddr(pgIdxBits-1, 0))
  val mpu_priv = Mux[UInt](Bool(usingVM) && (do_refill || io.req.bits.passthrough /* PTW */), PRV.S, Cat(io.ptw.status.debug, priv))
  
  //** PMP **//
  val pmp = Module(new PMPChecker(lgMaxSize))
  pmp.io.addr := mpu_physaddr
  pmp.io.size := io.req.bits.size
  pmp.io.pmp := (io.ptw.pmp: Seq[PMP])
  pmp.io.prv := mpu_priv
  val legal_address = edge.manager.findSafe(mpu_physaddr).reduce(_||_)
  // fast check property
  def fastCheck(member: TLManagerParameters => Boolean) =
    legal_address && edge.manager.fastProperty(mpu_physaddr, member, (b:Boolean) => Bool(b))
  val cacheable = fastCheck(_.supportsAcquireB) && (instruction || !usingDataScratchpad)
  val homogeneous = TLBPageLookup(edge.manager.managers, xLen, p(CacheBlockBytes), BigInt(1) << pgIdxBits)(mpu_physaddr).homogeneous
  val deny_access_to_debug = mpu_priv <= PRV.M && p(DebugModuleKey).map(dmp => dmp.address.contains(mpu_physaddr)).getOrElse(false)
  // protection flags
  val prot_r = fastCheck(_.supportsGet) && !deny_access_to_debug && pmp.io.r
  val prot_w = fastCheck(_.supportsPutFull) && !deny_access_to_debug && pmp.io.w
  val prot_x = fastCheck(_.executable) && !deny_access_to_debug && pmp.io.x
  val prot_pp = fastCheck(_.supportsPutPartial)
  val prot_al = fastCheck(_.supportsLogical)
  val prot_aa = fastCheck(_.supportsArithmetic)
  val prot_eff = fastCheck(Seq(RegionType.PUT_EFFECTS, RegionType.GET_EFFECTS) contains _.regionType)

  //** TLB sector/superpage/all_entries hits **//
  val sector_hits = sectored_entries(memIdx).map(_.sectorHit(vpn))
  val superpage_hits = superpage_entries.map(_.hit(vpn))
  val hitsVec = all_entries.map(vm_enabled && _.hit(vpn))
  val real_hits = hitsVec.asUInt
  val hits = Cat(!vm_enabled, real_hits)

  // permission bit arrays
  when (do_refill) {
    // get pte response from PTW
    val pte = io.ptw.resp.bits.pte
    // create new TLBEntryData
    val newEntry = Wire(new TLBEntryData)
    newEntry.ppn := pte.ppn
    newEntry.c := cacheable
    newEntry.u := pte.u
    newEntry.g := pte.g && pte.v
    newEntry.ae := io.ptw.resp.bits.ae
    newEntry.sr := pte.sr()
    newEntry.sw := pte.sw()
    newEntry.sx := pte.sx()
    newEntry.pr := prot_r
    newEntry.pw := prot_w
    newEntry.px := prot_x
    newEntry.ppp := prot_pp
    newEntry.pal := prot_al
    newEntry.paa := prot_aa
    newEntry.eff := prot_eff
    newEntry.fragmented_superpage := io.ptw.resp.bits.fragmented_superpage

    when (special_entry.nonEmpty && !io.ptw.resp.bits.homogeneous) {
      // special entry
      special_entry.foreach { e =>
        e.insert(r_refill_tag, io.ptw.resp.bits.level, newEntry)
        when (invalidate_refill) { e.invalidate() }
      }
    }.elsewhen (io.ptw.resp.bits.level < pgLevels-1) {
      // superpage entries
      for ((e, i) <- superpage_entries.zipWithIndex) when (r_superpage_repl_addr === i) {
        e.insert(r_refill_tag, io.ptw.resp.bits.level, newEntry)
        when (invalidate_refill) { e.invalidate() }
      }
    }.otherwise {
      // sectored entries
      val r_memIdx = r_refill_tag.extract(cfg.nSectors.log2 + cfg.nSets.log2 - 1, cfg.nSectors.log2)
      val waddr = Mux(r_sectored_hit, r_sectored_hit_addr, r_sectored_repl_addr)
      for ((e, i) <- sectored_entries(r_memIdx).zipWithIndex) when (waddr === i) {
        when (!r_sectored_hit) { e.invalidate() }
        e.insert(r_refill_tag, 0.U, newEntry)
        when (invalidate_refill) { e.invalidate() }
      }
    }
  }

  // get TLBEntryData by vpn
  val entries = all_entries.map(_.getData(vpn))
  val normal_entries = entries.take(ordinary_entries.size)
  // get physical page number by vpn
  val ppn = Mux1H(hitsVec :+ !vm_enabled, (all_entries zip entries).map{ case (entry, data) => entry.ppn(vpn, data) } :+ vpn(ppnBits-1, 0))

  //** Protection Flags **//
  val nPhysicalEntries = 1 + special_entry.size
  // PTW access error array
  val ptw_ae_array = Cat(false.B, entries.map(_.ae).asUInt)
  val priv_rw_ok = Mux(!priv_s || io.ptw.status.sum, entries.map(_.u).asUInt, 0.U) | Mux(priv_s, ~entries.map(_.u).asUInt, 0.U)
  val priv_x_ok = Mux(priv_s, ~entries.map(_.u).asUInt, entries.map(_.u).asUInt)
  // r/w/x array
  val r_array = Cat(true.B, priv_rw_ok & (entries.map(_.sr).asUInt | Mux(io.ptw.status.mxr, entries.map(_.sx).asUInt, UInt(0))))
  val w_array = Cat(true.B, priv_rw_ok & entries.map(_.sw).asUInt)
  val x_array = Cat(true.B, priv_x_ok & entries.map(_.sx).asUInt)
  // p r/w/x array
  val pr_array = Cat(Fill(nPhysicalEntries, prot_r), normal_entries.map(_.pr).asUInt) & ~ptw_ae_array
  val pw_array = Cat(Fill(nPhysicalEntries, prot_w), normal_entries.map(_.pw).asUInt) & ~ptw_ae_array
  val px_array = Cat(Fill(nPhysicalEntries, prot_x), normal_entries.map(_.px).asUInt) & ~ptw_ae_array
  // get/put effect array
  val eff_array = Cat(Fill(nPhysicalEntries, prot_eff), normal_entries.map(_.eff).asUInt)
  // cacheable array
  val c_array = Cat(Fill(nPhysicalEntries, cacheable), normal_entries.map(_.c).asUInt)
  // put-partial/amo-arithmetic/amo-logical array
  val ppp_array = Cat(Fill(nPhysicalEntries, prot_pp), normal_entries.map(_.ppp).asUInt)
  val paa_array = Cat(Fill(nPhysicalEntries, prot_aa), normal_entries.map(_.paa).asUInt)
  val pal_array = Cat(Fill(nPhysicalEntries, prot_al), normal_entries.map(_.pal).asUInt)
  // put-partial/amo-arithmetic/amo-logical array if cached
  val ppp_array_if_cached = ppp_array | c_array
  val paa_array_if_cached = paa_array | Mux(usingAtomicsInCache, c_array, 0.U)
  val pal_array_if_cached = pal_array | Mux(usingAtomicsInCache, c_array, 0.U)
  // prefetchable array
  val prefetchable_array = Cat((cacheable && homogeneous) << (nPhysicalEntries-1), normal_entries.map(_.c).asUInt)

  // misaligned virtual address
  val misaligned = (io.req.bits.vaddr & (UIntToOH(io.req.bits.size) - 1)).orR
  // bad virtual address
  val bad_va = if (!usingVM || (minPgLevels == pgLevels && vaddrBits == vaddrBitsExtended)) false.B else vm_enabled && {
    val nPgLevelChoices = pgLevels - minPgLevels + 1
    val minVAddrBits = pgIdxBits + minPgLevels * pgLevelBits
    (for (i <- 0 until nPgLevelChoices) yield {
      val mask = ((BigInt(1) << vaddrBitsExtended) - (BigInt(1) << (minVAddrBits + i * pgLevelBits - 1))).U
      val maskedVAddr = io.req.bits.vaddr & mask
      io.ptw.ptbr.additionalPgLevels === i && !(maskedVAddr === 0 || maskedVAddr === mask)
    }).orR
  }

  //** requested command **//
  val cmd_lrsc = Bool(usingAtomics) && io.req.bits.cmd.isOneOf(M_XLR, M_XSC)
  val cmd_amo_logical = Bool(usingAtomics) && isAMOLogical(io.req.bits.cmd)
  val cmd_amo_arithmetic = Bool(usingAtomics) && isAMOArithmetic(io.req.bits.cmd)
  val cmd_put_partial = io.req.bits.cmd === M_PWR
  val cmd_read = isRead(io.req.bits.cmd)
  val cmd_write = isWrite(io.req.bits.cmd)
  val cmd_write_perms = cmd_write ||
    io.req.bits.cmd.isOneOf(M_FLUSH_ALL, M_WOK) // not a write, but needs write permissions

  val lrscAllowed = Mux(Bool(usingDataScratchpad || usingAtomicsOnlyForIO), 0.U, c_array)
  //** load/store {access error, misaligned address, page fault} *//
  val ae_array =
    Mux(misaligned, eff_array, 0.U) |
    Mux(cmd_lrsc, ~lrscAllowed, 0.U)
  val ae_ld_array = Mux(cmd_read, ae_array | ~pr_array, 0.U)
  val ae_st_array =
    Mux(cmd_write_perms, ae_array | ~pw_array, 0.U) |
    Mux(cmd_put_partial, ~ppp_array_if_cached, 0.U) |
    Mux(cmd_amo_logical, ~pal_array_if_cached, 0.U) |
    Mux(cmd_amo_arithmetic, ~paa_array_if_cached, 0.U)
  val must_alloc_array =
    Mux(cmd_put_partial, ~ppp_array, 0.U) |
    Mux(cmd_amo_logical, ~paa_array, 0.U) |
    Mux(cmd_amo_arithmetic, ~pal_array, 0.U) |
    Mux(cmd_lrsc, ~0.U(pal_array.getWidth.W), 0.U)
  val ma_ld_array = Mux(misaligned && cmd_read, ~eff_array, 0.U)
  val ma_st_array = Mux(misaligned && cmd_write, ~eff_array, 0.U)
  val pf_ld_array = Mux(cmd_read, ~(r_array | ptw_ae_array), 0.U)
  val pf_st_array = Mux(cmd_write_perms, ~(w_array | ptw_ae_array), 0.U)
  val pf_inst_array = ~(x_array | ptw_ae_array)

  //** TLB hit/miss event **//
  val tlb_hit = real_hits.orR
  val tlb_miss = vm_enabled && !bad_va && !tlb_hit

  //** replacement policy //
  val sectored_plru = new SetAssocLRU(cfg.nSets, sectored_entries(0).size, "plru")
  val superpage_plru = new PseudoLRU(superpage_entries.size)
  when (io.req.valid && vm_enabled) {
    when (sector_hits.orR) { sectored_plru.access(memIdx, OHToUInt(sector_hits)) }
    when (superpage_hits.orR) { superpage_plru.access(OHToUInt(superpage_hits)) }
  }

  // Superpages create the possibility that two entries in the TLB may match.
  // This corresponds to a software bug, but we can't return complete garbage;
  // we must return either the old translation or the new translation.  This
  // isn't compatible with the Mux1H approach.  So, flush the TLB and report
  // a miss on duplicate entries.
  val multipleHits = PopCountAtLeast(real_hits, 2)

  //** TLB response to CPU **//
  io.req.ready := state === s_ready
  io.resp.pf.ld := (bad_va && cmd_read) || (pf_ld_array & hits).orR
  io.resp.pf.st := (bad_va && cmd_write_perms) || (pf_st_array & hits).orR
  io.resp.pf.inst := bad_va || (pf_inst_array & hits).orR
  io.resp.ae.ld := (ae_ld_array & hits).orR
  io.resp.ae.st := (ae_st_array & hits).orR
  io.resp.ae.inst := (~px_array & hits).orR
  io.resp.ma.ld := (ma_ld_array & hits).orR
  io.resp.ma.st := (ma_st_array & hits).orR
  io.resp.ma.inst := false // this is up to the pipeline to figure out
  io.resp.cacheable := (c_array & hits).orR
  io.resp.must_alloc := (must_alloc_array & hits).orR
  io.resp.prefetchable := (prefetchable_array & hits).orR && edge.manager.managers.forall(m => !m.supportsAcquireB || m.supportsHint)
  io.resp.miss := do_refill || tlb_miss || multipleHits
  io.resp.paddr := Cat(ppn, io.req.bits.vaddr(pgIdxBits-1, 0))

  //** PTW request from TLB **//
  io.ptw.req.valid := state === s_request
  io.ptw.req.bits.valid := !io.kill
  io.ptw.req.bits.bits.addr := r_refill_tag

  //** State Machine **//
  if (usingVM) {
    val sfence = io.sfence.valid
    when (io.req.fire() && tlb_miss) {
      state := s_request
      r_refill_tag := vpn

      // replaced address
      r_superpage_repl_addr := replacementEntry(superpage_entries, superpage_plru.way)
      r_sectored_repl_addr := replacementEntry(sectored_entries(memIdx), sectored_plru.way(memIdx))
      r_sectored_hit_addr := OHToUInt(sector_hits)
      r_sectored_hit := sector_hits.orR
    }
    when (state === s_request) {
      when (sfence) { state := s_ready }
      when (io.ptw.req.ready) { state := Mux(sfence, s_wait_invalidate, s_wait) }
      when (io.kill) { state := s_ready }
    }
    when (state === s_wait && sfence) {
      state := s_wait_invalidate
    }
    when (io.ptw.resp.valid) {
      state := s_ready
    }

    when (sfence) {
      assert(!io.sfence.bits.rs1 || (io.sfence.bits.addr >> pgIdxBits) === vpn)
      for (e <- all_real_entries) {
        when (io.sfence.bits.rs1) { e.invalidateVPN(vpn) }
        .elsewhen (io.sfence.bits.rs2) { e.invalidateNonGlobal() }
        .otherwise { e.invalidate() }
      }
    }
    when (multipleHits || reset) {
      all_real_entries.foreach(_.invalidate())
    }

    ccover(io.ptw.req.fire(), "MISS", "TLB miss")
    ccover(io.ptw.req.valid && !io.ptw.req.ready, "PTW_STALL", "TLB miss, but PTW busy")
    ccover(state === s_wait_invalidate, "SFENCE_DURING_REFILL", "flush TLB during TLB refill")
    ccover(sfence && !io.sfence.bits.rs1 && !io.sfence.bits.rs2, "SFENCE_ALL", "flush TLB")
    ccover(sfence && !io.sfence.bits.rs1 && io.sfence.bits.rs2, "SFENCE_ASID", "flush TLB ASID")
    ccover(sfence && io.sfence.bits.rs1 && !io.sfence.bits.rs2, "SFENCE_LINE", "flush TLB line")
    ccover(sfence && io.sfence.bits.rs1 && io.sfence.bits.rs2, "SFENCE_LINE_ASID", "flush TLB line/ASID")
    ccover(multipleHits, "MULTIPLE_HITS", "Two matching translations in TLB")
  }

  def ccover(cond: Bool, label: String, desc: String)(implicit sourceInfo: SourceInfo) =
    cover(cond, s"${if (instruction) "I" else "D"}TLB_$label", "MemorySystem;;" + desc)

  def replacementEntry(set: Seq[TLBEntry], alt: UInt) = {
    val valids = set.map(_.valid.orR).asUInt
    Mux(valids.andR, alt, PriorityEncoder(~valids))
  }
}
