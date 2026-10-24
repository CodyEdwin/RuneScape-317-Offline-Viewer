/**
 * Snapshot of the movement keys currently held, written by key events on the
 * EDT and read by the camera each frame. Keeping it separate keeps the input
 * listeners from knowing anything about camera physics.
 */
final class InputState {
    boolean forward;     // W
    boolean back;        // S
    boolean strafeLeft;  // A
    boolean strafeRight; // D
    boolean lookUp;      // Up arrow
    boolean lookDown;    // Down arrow
    boolean lookLeft;    // Left arrow
    boolean lookRight;   // Right arrow
    boolean sprint;      // Shift
    boolean jump;        // Space
    boolean crouch;      // Ctrl
}
