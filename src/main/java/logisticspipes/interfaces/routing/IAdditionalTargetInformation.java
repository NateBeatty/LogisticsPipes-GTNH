package logisticspipes.interfaces.routing;

public interface IAdditionalTargetInformation {

    /**
     * Whether an item sent with this information would be handled exactly as one sent with {@code other}: same module,
     * same slot. Orders for the same item to the same destination with the same target can then swap which of them a
     * sent item is counted against. Only the same object by default.
     */
    default boolean sameTargetAs(IAdditionalTargetInformation other) {
        return this == other;
    }
}
