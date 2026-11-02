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
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@Entity(tableName = "owned_cards")
data class OwnedCard(
    @PrimaryKey val id: String,
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
    val addedAt: Long = System.currentTimeMillis()
)

@Dao
interface CardDao {
    @Query("SELECT * FROM owned_cards ORDER BY addedAt DESC")
    fun all(): Flow<List<OwnedCard>>

    @Query("SELECT * FROM owned_cards WHERE id = :id")
    suspend fun byId(id: String): OwnedCard?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: OwnedCard)

    @Query("DELETE FROM owned_cards WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [OwnedCard::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): CardDao

    companion object {
        @Volatile
        private var inst: AppDb? = null

        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "pokescan.db")
                .build().also { inst = it }
        }
    }
}

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
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    fun search(q: String): List<ApiCard> {
        val url = "https://api.pokemontcg.io/v2/cards".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("pageSize", "20")
            .addQueryParameter("select", "id,name,number,rarity,set,images,tcgplayer")
            .build()
        var last: Exception? = null
        repeat(2) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
                    val arr = JSONObject(r.body!!.string()).getJSONArray("data")
                    return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
                }
            } catch (e: Exception) {
                last = e
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
