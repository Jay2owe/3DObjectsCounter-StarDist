package sc.fiji.oc3dsd.engine;

/**
 * Signals that detection or linking failed before a valid label image could be
 * produced. A {@code null} label image remains reserved for the distinct
 * "ran successfully, found nothing" outcome, which is not an error.
 */
public class DetectionRunFailureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DetectionRunFailureException(String message, Throwable cause) {
        super(safeMessage(message), cause);
    }

    public DetectionRunFailureException(String message) {
        super(safeMessage(message));
    }

    private static String safeMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "StarDist detection failed.";
        }
        return message.trim();
    }
}
