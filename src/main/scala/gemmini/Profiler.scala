package gemmini

import chisel3._
import chisel3.util._
import chisel3.experimental._
import freechips.rocketchip.tile.RoCCCommand
import freechips.rocketchip.util.PlusArg
import GemminiISA._
import Util._

object ProfileEvent{
    val DISABLE = 0

    val ROB_ALLOC = 1

    val ROB_ISSUE_LD = 2
    val ROB_ISSUE_EX = 3
    val ROB_ISSUE_ST = 4
    
    val ROB_COMPLETE = 5
    
    val ENTER_LD_CTRL = 6
    val ENTER_EX_CTRL = 7
    val ENTER_ST_CTRL = 8

    val LEAVE_LD_CTRL = 9
    val LEAVE_EX_CTRL = 10
    val LEAVE_ST_CTRL = 11

    val ENTER_DMA_READ  = 12
    val ENTER_DMA_WRITE = 13
    val LEAVE_DMA_READ  = 14
    val LEAVE_DMA_WRITE = 15

    val ENTER_SPAD_READ = 16
    val ENTER_SPAD_WRITE= 17
    val LEAVE_SPAD_READ = 18
    val LEAVE_SPAD_WRITE= 19

    val ENTER_MESH_CTRL = 20
    val LEAVE_MESH_CTRL = 21

    val ENTER_DEL_MESH  = 22
    val LEAVE_DEL_MESH  = 23

    val LD_CTRL_EXECUTE = 24
    val EX_CTRL_EXECUTE = 25
    val ST_CTRL_EXECUTE = 26
    
    val n = 27
}

class ProfileEventIO(ROB_ID_WIDTH : Int) extends Bundle{
    val event_signal = Output(Vec(ProfileEvent.n, Bool()))
    val event_id     = Output(Vec(ProfileEvent.n, UInt(ROB_ID_WIDTH.W)))

    private var connected = Array.fill(ProfileEvent.n)(false)

    def connectEventSignal(addr: Int, sig: UInt, id: UInt) = {
        event_signal(addr) := sig
        event_id(addr) := id
        connected(addr) = true
    }

    def collect[T <: Data](io: ProfileEventIO) = {
        for(i <-0 until ProfileEvent.n)
            if(io.connected(i)){
                if(connected(i)){
                    throw new IllegalStateException("Port " + i + " is already connected in another IO")
                }
                else{
                    connected(i) = true
                    event_signal(i) := io.event_signal(i)
                    event_id(i) := io.event_id(i)
                }
            }
    }
}

object ProfileEventIO {
    def init (io: ProfileEventIO) = {
        io.event_signal := 0.U.asTypeOf(io.event_signal.cloneType)
        io.event_id := 0.U.asTypeOf(io.event_id.cloneType)
    }
}

class ProfileIO [T <: Data](cmd_t: T, ROB_ID_WIDTH: Int) extends Bundle{
    val issue_cmd = new ReservationStationIssue(cmd_t, ROB_ID_WIDTH)
    val event_io = new ProfileEventIO(ROB_ID_WIDTH)
}

class ProfilerFile [T <: Data : Arithmetic, U <: Data, V <: Data](config: GemminiArrayConfig[T, U, V], 
                                                                  cmd_t: GemminiCmd) extends Module{
    import config._
    
    val io = IO(Flipped(new ProfileIO(cmd_t, ROB_ID_WIDTH)))
    
    val ldq :: exq :: stq :: Nil = Enum(3)
    val q_t = ldq.cloneType
    
    class Entry extends Bundle{
        val tag = UInt(32.W)
        val cmd = cmd_t.cloneType
    }

    val tag = RegInit(0.U(32.W))
    val clk_cnt = RegInit(0.U(32.W))
    clk_cnt := clk_cnt + 1.U

    val entries_ld = Reg(Vec(reservation_station_entries_ld, new Entry))
    val entries_ex = Reg(Vec(reservation_station_entries_ex, new Entry))
    val entries_st = Reg(Vec(reservation_station_entries_st, new Entry))
    val entries = entries_ld ++ entries_ex ++ entries_st

    io.issue_cmd.ready := true.B
    
    val signal = io.event_io.event_signal
    val id = io.event_io.event_id
    
    val new_entry = Wire(new Entry)
    new_entry.cmd := io.issue_cmd.cmd
    new_entry.tag := tag


    when(io.issue_cmd.valid && signal(ProfileEvent.ROB_ALLOC)){
        val type_width = log2Up(res_max_per_type)
        val queue_type = io.issue_cmd.rob_id(type_width + 1, type_width)
        val issue_id = io.issue_cmd.rob_id(type_width - 1, 0)
        val cmd = io.issue_cmd.cmd.cmd

        printf("0x%x/%d-%d/%d-%d-%d\n", 
                 new_entry.tag, 1.U, clk_cnt, cmd.inst.asUInt, cmd.rs1, cmd.rs2)

        Seq((ldq, entries_ld, reservation_station_entries_ld),
            (exq, entries_ex, reservation_station_entries_ex),
            (stq, entries_st, reservation_station_entries_st))
            .foreach { case(q, entries_type, entries_count) =>
                when(queue_type===q){
                    entries_type(issue_id) := new_entry
                }
            }
        tag := tag + 1.U
    }

    for(i <- 2 until ProfileEvent.n){
        when(signal(i)){
            val type_width = log2Up(res_max_per_type)
            val queue_type = id(i)(type_width + 1, type_width)
            val issue_id = id(i)(type_width - 1, 0)
            Seq((ldq, entries_ld, reservation_station_entries_ld),
            (exq, entries_ex, reservation_station_entries_ex),
            (stq, entries_st, reservation_station_entries_st))
            .foreach { case(q, entries_type, entries_count) =>
                when(queue_type===q){
                    val entry = entries_type(issue_id)
                    val cmd = entry.cmd.cmd
                    printf("0x%x/%d-%d/%d-%d-%d\n", 
                    entry.tag, i.U, clk_cnt,cmd.inst.asUInt, cmd.rs1, cmd.rs2)
                }
            }
        }
    }

}

class ProfilerController [T <: Data : Arithmetic, U <: Data, V <: Data](config: GemminiArrayConfig[T, U, V], 
                                                                  cmd_t: GemminiCmd) extends Module{
    import config._
                                                                  
    val io = IO(new Bundle{
        val profile_io = Flipped(new ProfileIO(cmd_t, ROB_ID_WIDTH))
    })
    val profiler_file = Module(new ProfilerFile(config, cmd_t))
    profiler_file.io <> io.profile_io
}