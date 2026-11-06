package logisticspipes.request;

import java.util.List;

import logisticspipes.request.resources.IResource;
import logisticspipes.routing.order.LinkedLogisticsOrderList;

public interface RequestLog {

    void handleMissingItems(List<IResource> resources);

    void handleSucessfullRequestOf(IResource item, LinkedLogisticsOrderList paticipating);

    void handleSucessfullRequestOfList(List<IResource> resources, LinkedLogisticsOrderList paticipating);

    /**
     * Whether this log wants to hear what a failed request was missing. Working that out plans every failed branch a
     * second time ({@link RequestTreeNode#recurseFailedRequestTree}), as costly as the request itself, so a log that
     * would throw the list away says no and the failure is reported without it.
     */
    default boolean wantsMissingItems() {
        return true;
    }
}
