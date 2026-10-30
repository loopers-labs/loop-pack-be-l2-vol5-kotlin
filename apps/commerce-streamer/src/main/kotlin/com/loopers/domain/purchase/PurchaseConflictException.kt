package com.loopers.domain.purchase

class PurchaseConflictException(
    eventId: String,
) : IllegalStateException("같은 eventId 로 다른 구매 사실이 왔다. eventId=$eventId")
