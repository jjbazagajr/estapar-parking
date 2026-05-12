package com.estapar.parking.revenue

import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class AddToRevenueListener(
    private val service: RevenueService,
) {

    @EventListener
    fun on(event: AddToRevenueEvent) {
        service.addRevenue(event)
    }
}
