package com.pokescan

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Perfil de usuário (local). A senha nunca é guardada: só o hash PBKDF2 + salt. */
@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val salt: String,
    val hash: String,
    val avatar: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** Carta de um perfil. profileId = 0 só existe para cartas de versões antigas, que são adotadas pelo primeiro perfil criado. */
@Entity(tableName = "owned_cards", primaryKeys = ["profileId", "id"])
data class OwnedCard(
    val profileId: Long = 0,
    val id: String,
    val name: String,
    val setId: String,
    val setName: String,
    val setSymbol: String = "",
    val number: String,
    val printedTotal: Int,
    val rarity: String?,
    val imageSmall: String,
    val imageLarge: String,
    val price: Double?,
    val quantity: Int = 1,
    val condition: String = "NM",
    val addedAt: Long = System.currentTimeMillis()
)

@Dao
interface CardDao {
    @Query("SELECT * FROM owned_cards WHERE profileId = :pid ORDER BY addedAt DESC")
    fun all(pid: Long): Flow<List<OwnedCard>>

    @Query("SELECT * FROM owned_cards WHERE profileId = :pid AND id = :id")
    suspend fun byId(pid: Long, id: String): OwnedCard?

    @Query("SELECT * FROM owned_cards WHERE profileId = :pid")
    suspend fun snapshot(pid: Long): List<OwnedCard>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: OwnedCard)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(cards: List<OwnedCard>)

    @Query("DELETE FROM owned_cards WHERE profileId = :pid AND id = :id")
    suspend fun delete(pid: Long, id: String)

    @Query("DELETE FROM owned_cards WHERE profileId = :pid")
    suspend fun clear(pid: Long)

    @Query("UPDATE owned_cards SET profileId = :pid WHERE profileId = 0")
    suspend fun adoptLegacy(pid: Long)

    @Query("SELECT * FROM profiles ORDER BY id")
    fun profiles(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun profile(id: Long): Profile?

    @Insert
    suspend fun insertProfile(p: Profile): Long

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfile(id: Long)

    @Query("UPDATE profiles SET salt = :salt, hash = :hash WHERE id = :id")
    suspend fun changePassword(id: Long, salt: String, hash: String)

    @Query("UPDATE profiles SET name = :name, avatar = :avatar WHERE id = :id")
    suspend fun updateProfile(id: Long, name: String, avatar: String)
}

/** v1 -> v2: cria perfis e separa as cartas por perfil, preservando as cartas já escaneadas. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `salt` TEXT NOT NULL, `hash` TEXT NOT NULL, " +
                "`avatar` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `owned_cards_new` (`profileId` INTEGER NOT NULL, `id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `setId` TEXT NOT NULL, `setName` TEXT NOT NULL, `setSymbol` TEXT NOT NULL, " +
                "`number` TEXT NOT NULL, `printedTotal` INTEGER NOT NULL, `rarity` TEXT, " +
                "`imageSmall` TEXT NOT NULL, `imageLarge` TEXT NOT NULL, `price` REAL, " +
                "`quantity` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`profileId`, `id`))"
        )
        db.execSQL(
            "INSERT INTO owned_cards_new (profileId, id, name, setId, setName, setSymbol, number, printedTotal, " +
                "rarity, imageSmall, imageLarge, price, quantity, addedAt) " +
                "SELECT 0, id, name, setId, setName, setSymbol, number, printedTotal, rarity, imageSmall, " +
                "imageLarge, price, quantity, addedAt FROM owned_cards"
        )
        db.execSQL("DROP TABLE owned_cards")
        db.execSQL("ALTER TABLE owned_cards_new RENAME TO owned_cards")
    }
}

/** v2 -> v3: cada carta ganha uma condição (NM, SP, MP, HP, D); as cartas existentes viram NM. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `owned_cards_new` (`profileId` INTEGER NOT NULL, `id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `setId` TEXT NOT NULL, `setName` TEXT NOT NULL, `setSymbol` TEXT NOT NULL, " +
                "`number` TEXT NOT NULL, `printedTotal` INTEGER NOT NULL, `rarity` TEXT, " +
                "`imageSmall` TEXT NOT NULL, `imageLarge` TEXT NOT NULL, `price` REAL, " +
                "`quantity` INTEGER NOT NULL, `condition` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`profileId`, `id`))"
        )
        db.execSQL(
            "INSERT INTO owned_cards_new (profileId, id, name, setId, setName, setSymbol, number, printedTotal, " +
                "rarity, imageSmall, imageLarge, price, quantity, condition, addedAt) " +
                "SELECT profileId, id, name, setId, setName, setSymbol, number, printedTotal, rarity, imageSmall, " +
                "imageLarge, price, quantity, 'NM', addedAt FROM owned_cards"
        )
        db.execSQL("DROP TABLE owned_cards")
        db.execSQL("ALTER TABLE owned_cards_new RENAME TO owned_cards")
    }
}

@Database(entities = [Profile::class, OwnedCard::class], version = 3, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): CardDao

    companion object {
        @Volatile
        private var inst: AppDb? = null

        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "pokescan.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { inst = it }
        }
    }
}

/** "25/165" quando o total do set é conhecido; só "25" caso contrário. */
fun numLabel(number: String, total: Int): String = if (total > 0) "$number/$total" else number

data class ApiCard(
    val id: String,
    val name: String,
    val number: String,
    val setId: String,
    val setName: String,
    val setSymbol: String = "",
    val printedTotal: Int,
    val rarity: String?,
    val imageSmall: String,
    val imageLarge: String,
    val price: Double?
)

/** Cliente da API gratuita https://pokemontcg.io (sem chave funciona, com limite de requisições). */
object PokeApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private class ClientError(msg: String) : RuntimeException(msg)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<ApiCard>>()

    /** Consulta com até 4 tentativas (espera 1s, 2s, 4s) para erros 5xx/timeouts; resultados ficam em cache. */
    fun search(q: String): List<ApiCard> {
        cache[q]?.let { return it }
        val url = "https://api.pokemontcg.io/v2/cards".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("pageSize", "20")
            .addQueryParameter("select", "id,name,number,rarity,set,images,tcgplayer")
            .build()
        var last: Exception? = null
        for (attempt in 0 until 4) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.code in 500..599) throw RuntimeException("HTTP ${r.code}")
                    if (!r.isSuccessful) throw ClientError("HTTP ${r.code}")
                    val arr = JSONObject(r.body!!.string()).getJSONArray("data")
                    val list = (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
                    if (list.isNotEmpty()) cache[q] = list
                    return list
                }
            } catch (e: ClientError) {
                throw e
            } catch (e: Exception) {
                last = e
                if (attempt < 3) Thread.sleep(1000L shl attempt)
            }
        }
        throw last ?: RuntimeException("Erro desconhecido")
    }

    private fun parse(o: JSONObject): ApiCard {
        val set = o.getJSONObject("set")
        val img = o.getJSONObject("images")
        var price: Double? = null
        val prices = o.optJSONObject("tcgplayer")?.optJSONObject("prices")
        if (prices != null) {
            val keys = prices.keys()
            while (keys.hasNext()) {
                val m = prices.optJSONObject(keys.next())?.optDouble("market", Double.NaN) ?: Double.NaN
                if (!m.isNaN()) {
                    price = m
                    break
                }
            }
        }
        return ApiCard(
            id = o.getString("id"),
            name = o.getString("name"),
            number = o.getString("number"),
            setId = set.getString("id"),
            setName = set.getString("name"),
            setSymbol = set.optJSONObject("images")?.optString("symbol") ?: "",
            printedTotal = set.optInt("printedTotal", set.optInt("total", 0)),
            rarity = o.optString("rarity").ifBlank { null },
            imageSmall = img.getString("small"),
            imageLarge = img.getString("large"),
            price = price
        )
    }
}
