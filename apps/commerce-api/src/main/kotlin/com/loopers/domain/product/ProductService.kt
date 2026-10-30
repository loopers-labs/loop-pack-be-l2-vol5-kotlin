package com.loopers.domain.product

import com.loopers.domain.shared.Money
import com.loopers.support.error.CoreException
import com.loopers.support.error.ErrorType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class ProductService(
    private val productRepository: ProductRepository,
    private val productSearchCache: ProductSearchCache,
    private val productStockService: ProductStockService,
) {
    /**
     * 재고는 생성에서 받지 않는다. 항상 0 으로 시작하고 재고 변경으로만 바꾼다.
     * 브랜드가 있는지는 다른 도메인이라 퍼사드가 먼저 본다.
     */
    @Transactional
    fun create(brandId: Long, name: String, price: Money): Product {
        val product = productRepository.save(
            Product(brandId = brandId, name = name, price = price, stock = Stock(INITIAL_STOCK)),
        )
        productStockService.sync(product.id, product.stock.quantity)

        return product
    }

    /**
     * 고객이 쓰는 모든 경로의 기준이다. 판매중지 상품은 **없는 것과 같은 응답**을 준다.
     * 중지됐다는 사실이 새어나가지 않아야 고객의 사용 대상에서 제외된 것이다.
     * 관리자 조회는 [get] 을 쓴다.
     */
    @Transactional(readOnly = true)
    fun getSellable(id: Long): Product {
        return get(id).takeIf { it.isSellable() }
            ?: throw CoreException(ErrorType.NOT_FOUND, "[id = $id] 상품을 찾을 수 없습니다.")
    }

    @Transactional(readOnly = true)
    fun getAll(): List<Product> = productRepository.findAll()

    /**
     * 브랜드가 내려갈 때 그 브랜드의 상품을 모두 판매중지로 바꾼다.
     * 상품 수만큼 UPDATE 가 돌지 않도록 한 번에 바꾼다.
     */
    @Transactional
    fun suspendAllByBrand(brandId: Long): Int = productRepository.suspendAllByBrandId(brandId)

    /**
     * 인기순의 **상위 구간만** 캐시를 거친다.
     *
     * 다른 정렬은 `price`·`created_at` 이 저장된 컬럼이라 인덱스가 순서를 들고 있고,
     * 캐시를 둘 이유가 없다. 인기순이라도 캐시가 담은 순위를 넘는 요청(뒤 페이지,
     * 더 큰 `size`)은 그대로 DB 로 보낸다. 담지 않은 구간을 캐시로 답할 수는 없다.
     */
    @Transactional(readOnly = true)
    fun search(search: ProductSearch): List<Product> {
        if (search.sort != ProductSort.LIKES_DESC || !coveredByCache(search)) {
            return productRepository.search(search)
        }

        productSearchCache.findTop(search.sort, search.brandId)
            ?.let { return orderedBy(it.sliceOf(search)) }

        if (!productSearchCache.tryStartRefill(search.sort, search.brandId)) {
            return awaitRefill(search)
        }

        try {
            // 미스일 때는 방금 읽은 상품을 그대로 쓴다. id 를 담아 두고 다시 읽으면 쿼리가 하나 는다.
            val top = loadTopRanked(search.brandId)
            productSearchCache.putTop(search.sort, search.brandId, top.map { it.id })

            return top.sliceOf(search)
        } finally {
            productSearchCache.finishRefill(search.sort, search.brandId)
        }
    }

    /**
     * 다른 요청이 채우는 중이면 그 결과를 기다린다. 만료 순간에 들어온 요청이 전부
     * 같은 집계 쿼리를 도는 것을 막는다 — 측정에서 41개가 동시에 돌아 각각 20초가 됐다.
     *
     * 기다려도 안 나오면 **직접 읽는다.** 채우던 쪽이 죽었을 수도 있고, 기다리다
     * 실패하느니 느려도 답하는 편이 낫다.
     *
     * 기다리는 동안 DB 커넥션을 쥐고 있지 않다. 이 지점까지 DB 를 한 번도 부르지 않아
     * 트랜잭션이 커넥션을 아직 얻지 않았다.
     */
    private fun awaitRefill(search: ProductSearch): List<Product> {
        val deadline = System.nanoTime() + ProductSearchCache.REFILL_WAIT.toNanos()

        while (System.nanoTime() < deadline) {
            productSearchCache.findTop(search.sort, search.brandId)
                ?.let { return orderedBy(it.sliceOf(search)) }

            try {
                Thread.sleep(ProductSearchCache.REFILL_POLL.toMillis())
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        return loadTopRanked(search.brandId).sliceOf(search)
    }

    private fun <T> List<T>.sliceOf(search: ProductSearch): List<T> =
        drop(search.offset.toInt()).take(search.size)

    /** 요청한 구간이 캐시가 담은 순위 안에 들어오는가. */
    private fun coveredByCache(search: ProductSearch): Boolean =
        search.offset + search.size <= ProductSearchCache.CACHED_RANKS

    /**
     * **요청한 만큼이 아니라 캐시가 담을 만큼**을 읽는다.
     * `size=5` 로 들어온 요청 때문에 5개짜리를 담아 두면, 뒤이은 `size=20` 요청이
     * 모자란 목록을 받는다.
     */
    private fun loadTopRanked(brandId: Long?): List<Product> =
        productRepository.search(
            ProductSearch(
                brandId = brandId,
                sort = ProductSort.LIKES_DESC,
                page = ProductSearch.DEFAULT_PAGE,
                size = ProductSearchCache.CACHED_RANKS,
            ),
        )

    /**
     * 캐시가 준 순서대로 상품을 돌려준다.
     *
     * 담아둔 것은 id 뿐이라 상품을 다시 읽는다. 그 사이 **삭제되거나 판매중지된 상품은 뺀다** —
     * 목록은 판매 중인 상품만 보여주기로 한 계약이고, 지난 순위를 쓰더라도 그 계약은 지킨다.
     * 그래서 한 페이지가 요청한 크기보다 짧아질 수 있다.
     *
     * `findAllByIds` 는 순서를 보장하지 않으므로 캐시의 순서로 다시 세운다.
     */
    private fun orderedBy(productIds: List<Long>): List<Product> {
        if (productIds.isEmpty()) return emptyList()

        val byId = productRepository.findAllByIds(productIds)
            .filter { it.isSellable() }
            .associateBy { it.id }

        return productIds.mapNotNull { byId[it] }
    }

    @Transactional(readOnly = true)
    fun countBy(brandId: Long?): Long = productRepository.countBy(brandId)

    /**
     * 삭제된 상품은 제외한다. 없는 id 는 조용히 빠진다.
     */
    @Transactional(readOnly = true)
    fun getAllByIds(ids: List<Long>): List<Product> = productRepository.findAllByIds(ids)

    @Transactional(readOnly = true)
    fun get(id: Long): Product {
        return productRepository.find(id)
            ?: throw CoreException(ErrorType.NOT_FOUND, "[id = $id] 상품을 찾을 수 없습니다.")
    }

    /**
     * 판매중지 상품은 바꿀 수 없다. 고객에게 안 보이는 채로 상품이 움직이면 안 되고,
     * 되살릴 길도 없으므로 없는 상품과 같이 취급한다.
     */
    @Transactional
    fun update(id: Long, name: String, price: Money): Product {
        val product = getSellable(id).also { it.update(name = name, price = price) }

        return productRepository.save(product)
    }

    /**
     * 최종 수량을 설정하지 않고 증가 값을 더한다. 음수만 거절하므로 0 은 재고를 바꾸지 않는다.
     * 판매중지 상품은 [update] 와 같은 이유로 거절한다.
     */
    @Transactional
    fun changeStock(id: Long, amount: Int): Product {
        if (amount < 0) throw CoreException(ErrorType.BAD_REQUEST, "재고 증가 값은 음수일 수 없습니다.")

        val product = getSellable(id)
        if (amount > 0) product.increaseStock(amount)

        return productRepository.save(product).also { productStockService.sync(it.id, it.stock.quantity) }
    }

    /**
     * 주문이 재고를 잡을 때 쓴다. 규칙은 [Product.decreaseStock] 이 지킨다.
     */
    @Transactional
    fun decreaseStock(product: Product, quantity: Int): Product {
        product.decreaseStock(quantity)

        return productRepository.save(product).also { productStockService.sync(it.id, it.stock.quantity) }
    }

    /**
     * 주문 취소가 재고를 되돌릴 때 쓴다.
     */
    @Transactional
    fun restoreStock(productId: Long, quantity: Int): Product {
        val product = get(productId).also { it.increaseStock(quantity) }

        return productRepository.save(product).also { productStockService.sync(it.id, it.stock.quantity) }
    }

    @Transactional
    fun delete(id: Long) {
        val product = get(id)
        if (!product.stock.isEmpty()) {
            throw CoreException(ErrorType.CONFLICT, "재고가 남아 있어 삭제할 수 없습니다. [재고 = ${product.stock}]")
        }

        product.delete()
        productRepository.save(product)
    }

    companion object {
        const val INITIAL_STOCK = 0
    }
}
