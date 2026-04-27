package hu.bme.aut.android.demo.database.tables

import org.jetbrains.exposed.dao.id.IntIdTable

object Rackets : IntIdTable("rackets") {
    val userId = reference("user_id", Users)

    val bladeManufacturer = varchar("blade_manufacturer", 100)
    val bladeModel = varchar("blade_model", 100)

    val fhRubberManufacturer = varchar("fh_rubber_manufacturer", 100)
    val fhRubberModel = varchar("fh_rubber_model", 100)
    val fhRubberColor = varchar("fh_rubber_color", 50)

    val bhRubberManufacturer = varchar("bh_rubber_manufacturer", 100)
    val bhRubberModel = varchar("bh_rubber_model", 100)
    val bhRubberColor = varchar("bh_rubber_color", 50)

    val isForSale = bool("is_for_sale").default(false)
}