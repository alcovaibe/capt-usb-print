package com.cryonum.captusbprint.core

/** Physical output position, including collated copies; independent of source page numbers. */
internal object DocumentContinuation {
    fun remaining(plan: JobSpecification, confirmed: Int): List<Int> {
        val order = plan.outputPageOrder
        require(confirmed in 0 until order.size)
        return order.drop(confirmed)
    }
    fun totalConfirmed(base: Int, segment: Int?, sheets: Int): Int {
        require(base in 0..sheets && (segment ?: 0) in 0..sheets - base)
        return base + (segment ?: 0)
    }
}
