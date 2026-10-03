package logisticspipes.pipes.upgrades;

import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.pipes.PipeLogisticsChassi;
import logisticspipes.pipes.basic.CoreRoutedPipe;

/**
 * Lets a chassis pipe be given a name and tags in its GUI, and lists it in the Statistics Table's crafter list. The
 * label stays in the pipe if the upgrade is taken out, but is neither shown nor listed until it is put back.
 */
public class LabelUpgrade implements IPipeUpgrade {

    @Override
    public boolean needsUpdate() {
        return false;
    }

    @Override
    public boolean isAllowedForPipe(CoreRoutedPipe pipe) {
        return pipe instanceof PipeLogisticsChassi;
    }

    @Override
    public boolean isAllowedForModule(LogisticsModule pipe) {
        return false;
    }

    @Override
    public String[] getAllowedPipes() {
        return new String[] { "chassi" };
    }

    @Override
    public String[] getAllowedModules() {
        return new String[] {};
    }
}
