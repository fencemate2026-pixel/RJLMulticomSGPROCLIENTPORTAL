package com.example.rjlmulticomsg_proclientportal.data.local

import com.example.rjlmulticomsg_proclientportal.domain.model.GsmCallerRole
import com.example.rjlmulticomsg_proclientportal.domain.phone.PhoneNumberNormalizer

/**
 * Commercial multi-unit site authorised callers (ESP32 GSM whitelist).
 * Sourced from client contact sheet — unit / site rep / staff mobiles.
 *
 * Clients call the gate SIM; only numbers on this list open the boom.
 */
object CommercialWhitelistSeed {

    const val ACCOUNT_ID = "acct_commercial_bc"
    const val SITE_NAME = "337 SETTLEMENT ROAD - THOMASTOWN · SLIDING GATE"
    const val SITE_ADDRESS = "337 Settlement Road, Thomastown VIC 3074"
    /** Gate type on this site (ESP32 dry-contact). */
    const val GATE_TYPE = "SLIDING GATE"
    /** Site SIM7600 / Multicom number people dial (0414 371 302). */
    const val GATE_SIM = "0414371302"

    data class Row(
        val unit: String,
        val name: String,
        val mobile: String,
        val roleLabel: String,
        val primaryContact: String = ""
    )

    /**
     * Flattened contact rows. Empty mobiles are skipped at seed time.
     * Role labels map to [GsmCallerRole].
     */
    val ROWS: List<Row> = listOf(
        // Unit 1 — Robert Ozzimo
        Row("1", "Robert Ozzimo", "0403311435", "Site Representative", "Robert Ozzimo"),
        Row("1", "Tony Lombardi", "0433223278", "Staff Member", "Robert Ozzimo"),
        Row("1", "Belinda Grech", "0439394754", "Staff Member", "Robert Ozzimo"),
        Row("1", "Andrew DeSanto", "0416574839", "Staff Member", "Robert Ozzimo"),
        Row("1", "Nicole Jahne", "0411722094", "Site Representative", "Robert Ozzimo"),
        Row("1", "Daniel Taylor", "0438164232", "Staff Member", "Robert Ozzimo"),
        Row("1", "Mario Tucci", "0423241974", "Staff Member", "Robert Ozzimo"),
        Row("1", "Paul Rametta", "0449094844", "Staff Member", "Robert Ozzimo"),
        Row("1", "Lenita Rametta", "0439115835", "Staff Member", "Robert Ozzimo"),
        Row("1", "Travis Whelan", "0455501293", "Staff Member", "Robert Ozzimo"),
        Row("1", "Joel Viavattene", "0448151014", "Staff Member", "Robert Ozzimo"),
        Row("1", "Emilia Faba", "0410655145", "Staff Member", "Robert Ozzimo"),
        Row("1", "Sherry Singh", "0456203040", "Staff Member", "Robert Ozzimo"),
        Row("1", "Michael Cetrola", "0497808484", "Staff Member", "Robert Ozzimo"),
        Row("1", "Tyler Burgess", "0467341830", "Staff Member", "Robert Ozzimo"),
        Row("1", "Lachlan Mills", "0427637130", "Staff Member", "Robert Ozzimo"),
        Row("1", "George Ioannou", "0421232029", "Staff Member", "Robert Ozzimo"),
        Row("1", "Christina Ioannou", "0432121170", "Staff Member", "Robert Ozzimo"),
        Row("1", "Michael Cananzi", "0413857837", "Staff Member", "Robert Ozzimo"),
        Row("1", "Nick Duryea", "0407985503", "Staff Member", "Robert Ozzimo"),
        // Unit 2 — Brent Richardson
        Row("2", "Brent Richardson", "0407673744", "Site Representative", "Brent Richardson"),
        Row("2", "Danny King", "0432115212", "Staff Member", "Brent Richardson"),
        Row("2", "Scott Brearley", "0434405072", "Staff Member", "Brent Richardson"),
        Row("2", "Dean Richardson", "0423214750", "Staff Member", "Brent Richardson"),
        Row("2", "Jake Osborne", "0404557314", "Staff Member", "Brent Richardson"),
        // Unit 3 — Suki Tan
        Row("3", "Suki Tan", "0402696870", "Site Representative", "Suki Tan"),
        Row("3", "Joseph", "0422039096", "Staff Member", "Suki Tan"),
        // Unit 4 — Tracey Lauretta
        Row("4", "Tracey Lauretta", "0433854243", "Site Representative", "Tracey Lauretta"),
        // Unit 6 — Giuseppe Tirella
        Row("6", "Giuseppe Tirella", "0418175165", "Site Representative", "Giuseppe Tirella"),
        Row("6", "Duncan Craig", "0402556082", "Staff Member", "Giuseppe Tirella"),
        // Unit 8 — Steve Callanan
        Row("8", "Steve Callanan", "0428284555", "Site Representative", "Steve Callanan"),
        Row("8", "Adam Griffiths", "0410043746", "Site Representative", "Steve Callanan"),
        Row("8", "Patricia Higgins", "0434147247", "Site Representative", "Steve Callanan"),
        // Unit 9 — Tony Paravizzini
        Row("9", "Tony Paravizzini", "0430000440", "Site Representative", "Tony Paravizzini"),
        Row("9", "Kathy Meyzis", "0438580195", "Staff Member", "Tony Paravizzini"),
        Row("9", "Qing Qin Zeng", "0422320108", "Staff Member", "Tony Paravizzini"),
        // Unit 10 — Maria-Louise Culcasi
        Row("10", "Maria-Louise Culcasi", "0417389882", "Site Representative", "Maria-Louise Culcasi"),
        Row("10", "Angelo Halarakis", "0400331510", "Site Representative", "Maria-Louise Culcasi"),
        Row("10", "Simon Rashleigh", "0432643585", "Site Representative", "Maria-Louise Culcasi"),
        // Unit 11 — Tony Ciantar
        Row("11", "Tony Ciantar", "0412466888", "Site Representative", "Tony Ciantar"),
        Row("11", "Jake Ciantar", "0420418089", "Staff Member", "Tony Ciantar"),
        // Unit 12 — Tina Kladis
        Row("12", "Tina Kladis", "0414698309", "Site Representative", "Tina Kladis"),
        Row("12", "Sarah Erulkar", "0412247769", "Staff Member", "Tina Kladis"),
        Row("12", "Sarah E (Vol)", "0494580646", "Staff Member", "Tina Kladis"),
        Row("12", "James Loo", "0488171088", "Other", "Tina Kladis"),
        Row("12", "Francesca Ligabo", "0412250078", "Site Representative", "Tina Kladis"),
        Row("12", "Bernadene Voss", "0413246704", "Staff Member", "Tina Kladis"),
        Row("12", "Maria Quigley", "0420510192", "Staff Member", "Tina Kladis"),
        Row("12", "Feona Wadsworth", "0419333747", "Staff Member", "Tina Kladis"),
        Row("12", "Leigh Villanti", "0416339339", "Other", "Tina Kladis"),
        Row("12", "Tania Fyffe", "0412333271", "Other", "Tina Kladis"),
        Row("12", "Natalie Jones", "0414284143", "Staff Member", "Tina Kladis"),
        Row("12", "Ibrahim Karabulut", "0420404660", "Cleaners", "Tina Kladis"),
        // Unit 13/14 — Pina Zagami
        Row("13/14", "Pina Zagami", "0412207078", "Site Representative", "Pina Zagami"),
        Row("13/14", "Robert Zagami", "0421152494", "Site Representative", "Pina Zagami"),
        Row("13/14", "Gabriella Costantino", "0423643618", "Staff Member", "Pina Zagami"),
        Row("13/14", "Anthony Costantino", "0432141362", "Staff Member", "Pina Zagami"),
        Row("13/14", "Vanessa Zagami", "0432017351", "Staff Member", "Pina Zagami"),
        Row("13/14", "Bart Zagami", "0412411678", "Site Representative", "Pina Zagami"),
        // Unit 16 — Peter Chrysostomou (no extra mobiles)
        Row("16", "Peter Chrysostomou", "0421706508", "Site Representative", "Peter Chrysostomou"),
        // Unit 17 — Trish Nardella
        Row("17", "Trish Nardella", "0409188922", "Site Representative", "Trish Nardella"),
        Row("17", "Jaiden Nardella", "0401755211", "Other", "Trish Nardella"),
        // Unit 18 — Craig Martin
        Row("18", "Craig Martin", "0433330790", "Site Representative", "Craig Martin"),
        Row("18", "Gavin Walsh", "0407208391", "Staff Member", "Craig Martin"),
        // Unit 19 — Christopher Terpos
        Row("19", "Christopher Terpos", "0419305556", "Staff Member", "Christopher Terpos"),
        Row("19", "Lisa Michalson", "0439119784", "Staff Member", "Christopher Terpos"),
        Row("19", "Garth Michalson", "0448100000", "Staff Member", "Christopher Terpos"),
        Row("19", "Brendon Vuntarde", "0412004157", "Staff Member", "Christopher Terpos"),
        Row("19", "Marecel Nedim", "0415334687", "Staff Member", "Christopher Terpos"),
        Row("19", "Laura Voskresensky", "0402747740", "Staff Member", "Christopher Terpos"),
        Row("19", "Tanya Yako", "0481227364", "Staff Member", "Christopher Terpos"),
        Row("19", "Chris Terpos", "0419305556", "Staff Member", "Christopher Terpos"),
        // Unit 20 — Jie Huang
        Row("20", "Jie Huang", "0478961783", "Site Representative", "Jie Huang"),
        Row("20", "Yuxin Yang", "0452578046", "Staff Member", "Jie Huang"),
        Row("20", "Weiwei Lu", "0404121218", "Contractor", "Jie Huang"),
        Row("20", "Zhen Wei", "0400510989", "Staff Member", "Jie Huang"),
        // Unit 21 — Dean / Hannah Loney
        Row("21", "Dean Loney", "0422741385", "Site Representative", "Dean Loney"),
        Row("21", "Jim Seltsiotis", "0432105574", "Staff Member", "Dean Loney"),
        Row("21", "Hannah Loney", "0401377373", "Site Representative", "Hannah Loney"),
        Row("21", "Alex Stanton", "0423424334", "Site Representative", "Hannah Loney"),
        Row("21", "Karlie", "0417102131", "Site Representative", "Hannah Loney"),
        Row("21", "Jo", "0417169900", "Site Representative", "Hannah Loney"),
        Row("21", "Jake", "0406180888", "Contractor", "Hannah Loney"),
        // Unit 22 — Carli Bates
        Row("22", "Carli Bates", "0413992297", "Staff Member", "Carli Bates"),
        Row("22", "John Grant", "0438542956", "Staff Member", "Carli Bates"),
        Row("22", "Stephen Tracey", "0450603541", "Staff Member", "Carli Bates"),
        // Unit 25 — Tilak / Sam
        Row("25", "Tilak Dave", "0432972737", "Staff Member", "Tilak Dave"),
        Row("25", "SAUMYA", "0452191561", "Site Representative", "Tilak Dave"),
        Row("25", "Sam K", "0452191561", "Site Representative", "Sam K"),
        Row("25", "Tilak", "0432972737", "Staff Member", "Sam K"),
        // Unit 27 — Terence Jape
        Row("27", "Terence Jape", "0432515416", "Site Representative", "Terence Jape"),
        // RJL admin handset — authorised to call the GATE SIM and open
        Row("RJL", "RJL Admin", "0400101132", "Site Representative", "RJL Commercial")
    )

    fun mapRole(label: String): GsmCallerRole = when {
        label.contains("Representative", ignoreCase = true) -> GsmCallerRole.OWNER
        label.contains("Staff", ignoreCase = true) -> GsmCallerRole.STAFF
        label.contains("Clean", ignoreCase = true) -> GsmCallerRole.STAFF
        label.contains("Contractor", ignoreCase = true) -> GsmCallerRole.TEMPORARY
        else -> GsmCallerRole.MEMBER
    }

    /**
     * Build unique callers by E.164 (first name wins; notes merge unit info).
     */
    fun buildEntities(accountId: String, nowMs: Long): List<GsmCallerEntity> {
        val byPhone = linkedMapOf<String, GsmCallerEntity>()
        for (row in ROWS) {
            val name = row.name.trim()
            if (name.isEmpty()) continue
            val e164 = when (val r = PhoneNumberNormalizer.normalize(row.mobile)) {
                is PhoneNumberNormalizer.Result.Valid -> r.e164
                else -> continue
            }
            val role = mapRole(row.roleLabel)
            val notes = buildString {
                append("Unit ").append(row.unit)
                if (row.primaryContact.isNotBlank()) {
                    append(" · Primary: ").append(row.primaryContact)
                }
                append(" · ").append(row.roleLabel)
            }
            val existing = byPhone[e164]
            if (existing == null) {
                byPhone[e164] = GsmCallerEntity(
                    id = "bc_${e164.filter { it.isDigit() }}",
                    accountId = accountId,
                    displayName = name,
                    phoneNumberE164 = e164,
                    enabled = true,
                    role = role.name,
                    notes = notes,
                    createdBy = "seed",
                    createdAt = nowMs,
                    updatedBy = "seed",
                    updatedAt = nowMs,
                    pendingSync = false
                )
            } else {
                // Same phone listed twice — keep first name, append unit note
                byPhone[e164] = existing.copy(
                    notes = existing.notes + " | " + notes
                )
            }
        }
        return byPhone.values.toList()
    }
}
