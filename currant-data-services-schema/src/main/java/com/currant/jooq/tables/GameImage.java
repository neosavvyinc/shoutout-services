/**
 * This class is generated by jOOQ
 */
package com.currant.jooq.tables;

/**
 * This class is generated by jOOQ.
 */
@javax.annotation.Generated(value    = { "http://www.jooq.org", "3.1.0" },
                            comments = "This class is generated by jOOQ")
@java.lang.SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class GameImage extends org.jooq.impl.TableImpl<com.currant.jooq.tables.records.GameImageRecord> {

	private static final long serialVersionUID = -1494833334;

	/**
	 * The singleton instance of <code>public.game_image</code>
	 */
	public static final com.currant.jooq.tables.GameImage GAME_IMAGE = new com.currant.jooq.tables.GameImage();

	/**
	 * The class holding records for this type
	 */
	@Override
	public java.lang.Class<com.currant.jooq.tables.records.GameImageRecord> getRecordType() {
		return com.currant.jooq.tables.records.GameImageRecord.class;
	}

	/**
	 * The column <code>public.game_image.game_id</code>. 
	 */
	public final org.jooq.TableField<com.currant.jooq.tables.records.GameImageRecord, java.lang.Long> GAME_ID = createField("game_id", org.jooq.impl.SQLDataType.BIGINT, this);

	/**
	 * The column <code>public.game_image.image_url</code>. 
	 */
	public final org.jooq.TableField<com.currant.jooq.tables.records.GameImageRecord, java.lang.String> IMAGE_URL = createField("image_url", org.jooq.impl.SQLDataType.VARCHAR.length(50), this);

	/**
	 * The column <code>public.game_image.sort_order</code>. 
	 */
	public final org.jooq.TableField<com.currant.jooq.tables.records.GameImageRecord, java.lang.Integer> SORT_ORDER = createField("sort_order", org.jooq.impl.SQLDataType.INTEGER, this);

	/**
	 * Create a <code>public.game_image</code> table reference
	 */
	public GameImage() {
		super("game_image", com.currant.jooq.Public.PUBLIC);
	}

	/**
	 * Create an aliased <code>public.game_image</code> table reference
	 */
	public GameImage(java.lang.String alias) {
		super(alias, com.currant.jooq.Public.PUBLIC, com.currant.jooq.tables.GameImage.GAME_IMAGE);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public java.util.List<org.jooq.UniqueKey<com.currant.jooq.tables.records.GameImageRecord>> getKeys() {
		return java.util.Arrays.<org.jooq.UniqueKey<com.currant.jooq.tables.records.GameImageRecord>>asList(com.currant.jooq.Keys.GAME_IMAGE_UNIQUE);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public java.util.List<org.jooq.ForeignKey<com.currant.jooq.tables.records.GameImageRecord, ?>> getReferences() {
		return java.util.Arrays.<org.jooq.ForeignKey<com.currant.jooq.tables.records.GameImageRecord, ?>>asList(com.currant.jooq.Keys.GAME_IMAGE__GAME_IMAGE_GAME_ID_FKEY);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public com.currant.jooq.tables.GameImage as(java.lang.String alias) {
		return new com.currant.jooq.tables.GameImage(alias);
	}
}
