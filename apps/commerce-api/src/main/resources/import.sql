alter table products add constraint fk_products_brand foreign key (brand_id) references brands (id) on delete restrict;
alter table orders add constraint fk_orders_user foreign key (user_id) references users (id) on delete restrict;
alter table order_items add constraint fk_order_items_product foreign key (product_id) references products (id) on delete restrict;
