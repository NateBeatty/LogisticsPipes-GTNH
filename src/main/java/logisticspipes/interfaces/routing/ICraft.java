package logisticspipes.interfaces.routing;

import java.util.Collections;
import java.util.List;

import logisticspipes.request.ICraftingTemplate;
import logisticspipes.request.IPromise;
import logisticspipes.request.resources.IResource;

public interface ICraft extends IProvide, IRequest {

    void registerExtras(IPromise promise);

    ICraftingTemplate addCrafting(IResource type);

    /**
     * Every recipe this pipe offers for the resource, preferred first. A pipe with one crafter offers at most the one
     * {@link #addCrafting} answers with; a chassis offers one per crafting module that makes it, so the planner can
     * fall back to a later module's recipe when an earlier one can't be fully supplied.
     */
    default List<ICraftingTemplate> addCraftingAll(IResource type) {
        ICraftingTemplate template = addCrafting(type);
        return template == null ? Collections.emptyList() : Collections.singletonList(template);
    }

    boolean canCraft(IResource toCraft);

    int getTodo();
}
