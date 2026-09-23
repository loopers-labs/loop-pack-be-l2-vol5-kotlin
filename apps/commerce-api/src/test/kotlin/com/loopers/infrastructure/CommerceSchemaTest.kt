package com.loopers.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
class CommerceSchemaTest @Autowired constructor(private val jdbc: JdbcTemplate) {
    @Test
    fun `foreign keys and check constraints protect aggregate identifiers and values`() {
        val foreignKeys = jdbc.queryForList(
            "select constraint_name from information_schema.referential_constraints " +
                "where constraint_schema = database() and constraint_name in (?, ?, ?)",
            String::class.java,
            "fk_products_brand",
            "fk_orders_user",
            "fk_order_items_product",
        )
        assertThat(foreignKeys).containsExactlyInAnyOrder("fk_products_brand", "fk_orders_user", "fk_order_items_product")
        val uniqueColumns = jdbc.queryForList(
            "select table_name, group_concat(column_name order by seq_in_index) as columns_in_order " +
                "from information_schema.statistics where table_schema = database() " +
                "and table_name in ('product_likes', 'order_items') and non_unique = 0 " +
                "group by table_name, index_name",
        )
        assertThat(uniqueColumns).anySatisfy { row ->
            assertThat(row["TABLE_NAME"]).isEqualTo("product_likes")
            assertThat(row["COLUMNS_IN_ORDER"]).isEqualTo("user_id,product_id")
        }
        assertThat(uniqueColumns).anySatisfy { row ->
            assertThat(row["TABLE_NAME"]).isEqualTo("order_items")
            assertThat(row["COLUMNS_IN_ORDER"]).isEqualTo("order_id,product_id")
        }
        val userColumns = jdbc.queryForList(
            "select column_name from information_schema.columns where table_schema = database() and table_name = 'users'",
            String::class.java,
        )
        assertThat(userColumns).doesNotContain("point_balance", "deleted_at")
        val pointColumns = jdbc.queryForList(
            "select column_name from information_schema.columns where table_schema = database() and table_name = 'point_balances'",
            String::class.java,
        )
        assertThat(pointColumns).contains("user_id", "balance")
        val pointPrimaryKey = jdbc.queryForObject(
            "select count(*) from information_schema.table_constraints " +
                "where constraint_schema = database() and table_name = 'point_balances' " +
                "and constraint_type = 'PRIMARY KEY'",
            Long::class.java,
        ) ?: 0L
        assertThat(pointPrimaryKey).isEqualTo(1L)
        val checkCount = jdbc.queryForObject(
            "select count(*) from information_schema.table_constraints " +
                "where constraint_schema = database() and table_name in ('point_balances', 'products', 'orders', 'order_items') " +
                "and constraint_type = 'CHECK'",
            Long::class.java,
        ) ?: 0L
        assertThat(checkCount).isGreaterThanOrEqualTo(4)
    }
}
