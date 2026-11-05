package logisticspipes.modules;

import java.util.List;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import logisticspipes.interfaces.IInventoryUtil;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.pipes.PipeLogisticsChassi.ChassiTargetInformation;
import logisticspipes.utils.SinkDiag;
import logisticspipes.utils.SinkReply;
import logisticspipes.utils.SinkReply.FixedPriority;
import logisticspipes.utils.item.ItemIdentifier;

public class ModulePolymorphicItemSink extends LogisticsModule {

    public ModulePolymorphicItemSink() {}

    private SinkReply _sinkReply;

    @Override
    public void registerPosition(ModulePositionType slot, int positionInt) {
        super.registerPosition(slot, positionInt);
        _sinkReply = new SinkReply(
                FixedPriority.ItemSink,
                0,
                true,
                false,
                3,
                0,
                new ChassiTargetInformation(getPositionInt()));
    }

    @Override
    public SinkReply sinksItem(ItemIdentifier item, int bestPriority, int bestCustomPriority, boolean allowDefault,
            boolean includeInTransit) {
        boolean diag = SinkDiag.isActive();
        if (bestPriority > _sinkReply.fixedPriority.ordinal() || (bestPriority == _sinkReply.fixedPriority.ordinal()
                && bestCustomPriority >= _sinkReply.customPriority)) {
            if (diag) {
                SinkDiag.log("  polymorphic refused: a nearer or better sink already won");
            }
            return null;
        }
        IInventoryUtil targetInventory = _service.getSneakyInventory(false, slot, positionInt);
        if (targetInventory == null) {
            if (diag) {
                SinkDiag.log("  polymorphic refused: no inventory in front of it");
            }
            return null;
        }

        if (!targetInventory.containsUndamagedItem(item.getUndamaged())) {
            if (diag) {
                SinkDiag.log(
                        "  polymorphic refused: item not matched in " + targetInventory.getClass().getSimpleName()
                                + ", "
                                + SinkDiag.describeMatches(targetInventory, item));
            }
            return null;
        }

        if (_service.canUseEnergy(3)) {
            return _sinkReply;
        }
        if (diag) {
            SinkDiag.log("  polymorphic refused: not enough power");
        }
        return null;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbttagcompound) {}

    @Override
    public void writeToNBT(NBTTagCompound nbttagcompound) {}

    @Override
    public LogisticsModule getSubModule(int slot) {
        return null;
    }

    @Override
    public void tick() {}

    @Override
    public final int getX() {
        return _service.getX();
    }

    @Override
    public final int getY() {
        return _service.getY();
    }

    @Override
    public final int getZ() {
        return _service.getZ();
    }

    @Override
    public boolean hasGenericInterests() {
        return false;
    }

    // TODO: SINK UNDAMAGED MATCH CORRECTLY!

    @Override
    public List<ItemIdentifier> getSpecificInterests() {
        return null;
    }

    @Override
    public boolean interestedInAttachedInventory() {
        return true; // by definition :)
    }

    @Override
    public boolean interestedInUndamagedID() {
        return true;
    }

    @Override
    public boolean recievePassive() {
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIconTexture(IIconRegister register) {
        return register.registerIcon("logisticspipes:itemModule/ModulePolymorphicItemSink");
    }
}
