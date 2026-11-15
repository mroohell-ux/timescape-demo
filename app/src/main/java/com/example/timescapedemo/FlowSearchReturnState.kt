package com.example.timescapedemo

class FlowSearchReturnState private constructor(
    val flowId: Long,
    private val positions: MutableMap<Long, CardFlowPosition>
) {
    fun positionFor(flow: CardFlow): CardFlowPosition? = positions[flow.id]?.resolved(flow.cards)

    fun onCardRemoved(flow: CardFlow, cardId: Long) {
        positions[flow.id]?.let { position ->
            positions[flow.id] = position.afterRemoving(cardId, flow.cards)
        }
    }

    companion object {
        fun capture(flows: List<CardFlow>, flowId: Long): FlowSearchReturnState =
            FlowSearchReturnState(flowId, flows.associate { flow ->
                flow.id to CardFlowPosition(
                    flow.lastViewedCardId, flow.lastViewedCardIndex, flow.lastViewedCardFocused
                )
            }.toMutableMap())
    }
}
