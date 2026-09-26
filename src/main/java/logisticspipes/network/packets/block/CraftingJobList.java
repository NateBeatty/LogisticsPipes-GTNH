package logisticspipes.network.packets.block;

import java.io.IOException;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;

import cpw.mods.fml.client.FMLClientHandler;
import logisticspipes.gui.GuiStatistics;
import logisticspipes.network.LPDataInputStream;
import logisticspipes.network.LPDataOutputStream;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.routing.order.CraftingJob;
import logisticspipes.routing.order.CraftingJobInfo;
import logisticspipes.routing.order.CraftingJobs;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/** The server's answer to {@link RequestCraftingJobs}: the live jobs, the recently ended ones, and one job's orders. */
@Accessors(chain = true)
public class CraftingJobList extends ModernPacket {

    @Getter
    @Setter
    private List<CraftingJobInfo> jobs;

    /** Jobs that ended in the last few seconds, so the client can show "Done" or "Cancelled" instead of guessing. */
    @Getter
    @Setter
    private List<CraftingJobs.Ended> ended;

    @Getter
    @Setter
    private int detailJobId = -1;

    @Getter
    @Setter
    private List<CraftingJob.WaitingOn> detail;

    public CraftingJobList(int id) {
        super(id);
    }

    @Override
    public void processPacket(EntityPlayer player) {
        if (FMLClientHandler.instance().getClient().currentScreen instanceof GuiStatistics) {
            ((GuiStatistics) FMLClientHandler.instance().getClient().currentScreen)
                    .handleJobList(jobs, ended, detailJobId, detail);
        }
    }

    @Override
    public void writeData(LPDataOutputStream data) throws IOException {
        data.writeList(jobs, (out, job) -> job.write(out));
        data.writeList(ended, (out, e) -> {
            out.writeInt(e.id);
            out.writeByte(e.end.ordinal());
            out.writeBoolean(e.finalInfo != null);
            if (e.finalInfo != null) {
                e.finalInfo.write(out);
            }
        });
        data.writeInt(detailJobId);
        data.writeList(detail, (out, waiting) -> waiting.write(out));
    }

    @Override
    public void readData(LPDataInputStream data) throws IOException {
        jobs = data.readList(CraftingJobInfo::read);
        ended = data.readList(in -> {
            int id = in.readInt();
            CraftingJob.End end = CraftingJob.End.values()[in.readByte()];
            CraftingJobInfo finalInfo = in.readBoolean() ? CraftingJobInfo.read(in) : null;
            return new CraftingJobs.Ended(id, end, 0, finalInfo);
        });
        detailJobId = data.readInt();
        detail = data.readList(CraftingJob.WaitingOn::read);
    }

    @Override
    public ModernPacket template() {
        return new CraftingJobList(getId());
    }
}
