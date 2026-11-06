package com.loopers.application.purchase

import com.loopers.domain.purchase.PurchaseActivityCommand
import com.loopers.domain.purchase.PurchaseActivityService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PurchaseActivityFacade(
    private val purchaseActivityService: PurchaseActivityService,
) {
    @Transactional
    fun record(command: PurchaseActivityCommand) {
        purchaseActivityService.record(command)
    }

    fun conflictsWith(command: PurchaseActivityCommand): Boolean = purchaseActivityService.conflictsWith(command)
}
