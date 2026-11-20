package android.os;

/** Compile-only stub for hidden platform android.os.UEventObserver. Never packaged into DEX. */
public abstract class UEventObserver {
    public static final class UEvent {
        public String get(String key) { throw new RuntimeException("stub"); }
    }
    public final void startObserving(String match) { throw new RuntimeException("stub"); }
    public final void stopObserving() { throw new RuntimeException("stub"); }
    public abstract void onUEvent(UEvent event);
}
