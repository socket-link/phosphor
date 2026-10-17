package link.socket.phosphor.field

/**
 * How cells travel during a [FormMorph]: the shape of the detour each takes on
 * its way from one form's position to the other's. The spring is the motion;
 * the choreography only bends its target sideways for the first part of the
 * trip, then lets it go.
 */
enum class Choreography {
    /**
     * A gentle arc whose size scales with how far the cell has to go, capped so
     * a cell crossing the orb does not loop around it: `min(0.2, distance × 0.13)`
     * in unit-sphere space. The default; reads as the body reshaping.
     */
    FLOW,

    /** Every cell flies out `0.55` units before settling: the body bursts and regathers. */
    SCATTER,

    /** No arc. Each cell takes the straight line its spring gives it. */
    DIRECT,
}
