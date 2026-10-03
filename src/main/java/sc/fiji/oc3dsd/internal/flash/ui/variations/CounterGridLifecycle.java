package sc.fiji.oc3dsd.internal.flash.ui.variations;

import java.util.List;

/** Counter adapter for FLASH's package-private, ownership-aware terminal cleanup. */
public final class CounterGridLifecycle {
    private CounterGridLifecycle() {}
    public static void close(List<VariationCellPanel> cells) {
        VariationCellPanel.disposeAllImages(cells);
    }
}
