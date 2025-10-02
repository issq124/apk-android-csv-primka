
package com.metrax.primkaapp

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.opencsv.CSVReaderBuilder
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.DecimalFormat

data class Row(
    val datum: String, val brojPrimke: String, val dobavljac: String, val brojUlaznog: String,
    val sifraArtikla: String, val nazivArtikla: String, val jmj: String,
    val kolicina: String, val nabavnaEur: String, val nabavnaPoKom: String,
    val nabavnaUkupno: String, val nabavnaVrijednost: String,
    val extra: Map<String, String> = emptyMap()
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App(contentResolver) } }
    }
}

@Composable
fun App(cr: ContentResolver) {
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var primka by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf(listOf<Row>()) }
    var msg by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) fileUri = it
    }

    LaunchedEffect(fileUri) {
        fileUri?.let { uri ->
            try {
                rows = loadRows(cr, uri)
                msg = "Učitano: ${rows.size} redaka"
            } catch (e: Exception) {
                msg = "Greška pri učitavanju: ${e.message}"
            }
        }
    }

    Scaffold { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Text("Primka PDF (CSV/XLSX)", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) }) {
                    Text("Učitaj CSV/XLSX")
                }
                OutlinedTextField(
                    value = primka,
                    onValueChange = { primka = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Broj primke") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Spacer(Modifier.height(8.dp)); Text(msg)
            Spacer(Modifier.height(16.dp))
            Button(
                enabled = fileUri != null && primka.isNotEmpty() && rows.isNotEmpty(),
                onClick = {
                    val data = rows.filter { it.brojPrimke == primka }
                    if (data.isEmpty()) { msg = "Nema redaka za primku $primka"; return@Button }
                    val uri = buildPdf(cr, primka, data)
                    msg = if (uri != null) "PDF spremljen: $uri" else "PDF nije spremljen"
                    uri?.let {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(it, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try { (cr as? android.content.ContextWrapper)?.baseContext?.startActivity(intent) } catch (_: Exception) {}
                    }
                }
            ) { Text("Generiraj PDF") }
        }
    }
}

private fun loadRows(cr: ContentResolver, uri: Uri): List<Row> {
    val name = getName(cr, uri).lowercase()
    return if (name.endsWith(".xlsx")) readXlsx(cr, uri) else readCsv(cr, uri)
}

private fun getName(cr: ContentResolver, uri: Uri): String {
    cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        val idx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        if (c.moveToFirst()) return c.getString(idx)
    }
    return "file"
}

private fun readCsv(cr: ContentResolver, uri: Uri): List<Row> {
    cr.openInputStream(uri).use { input ->
        val reader = CSVReaderBuilder(BufferedReader(InputStreamReader(input!!, Charsets.UTF_8))).build()
        val all = reader.readAll()
        if (all.isEmpty()) return emptyList()
        val header = all.first().map { it.trim() }
        fun get(map: Map<String, Int>, row: Array<String>, key: String) : String {
            val idx = map.entries.firstOrNull { it.key.equals(key, true) }?.value ?: return ""
            return row.getOrNull(idx)?.trim().orEmpty()
        }
        val index = header.mapIndexed { i, h -> h to i }.toMap()
        val out = ArrayList<Row>()
        for (arr in all.drop(1)) {
            val m = index
            out += Row(
                datum = get(m, arr, "Datum"),
                brojPrimke = get(m, arr, "Broj primke"),
                dobavljac = get(m, arr, "Dobavljač"),
                brojUlaznog = get(m, arr, "Broj ulaznog računa"),
                sifraArtikla = get(m, arr, "Šifra artikla"),
                nazivArtikla = get(m, arr, "Naziv artikla"),
                jmj = get(m, arr, "Jmj."),
                kolicina = get(m, arr, "Količina (+)"),
                nabavnaEur = get(m, arr, "Nabavna cijena (EUR)"),
                nabavnaPoKom = get(m, arr, "Nabavna cijena po kom."),
                nabavnaUkupno = get(m, arr, "Nabavna cijena ukupni iznos"),
                nabavnaVrijednost = get(m, arr, "Nabavna vrijednost"),
                extra = header.withIndex().associate { it.value to (arr.getOrNull(it.index)?.trim().orEmpty()) }
            )
        }
        return out
    }
}

private fun readXlsx(cr: ContentResolver, uri: Uri): List<Row> {
    cr.openInputStream(uri).use { input ->
        val wb = XSSFWorkbook(input)
        val sheet = wb.getSheetAt(0) // pretpostavi prvi sheet ili traži "Primke"
        val headerRow = sheet.getRow(sheet.firstRowNum) ?: return emptyList()
        val header = (0 until headerRow.lastCellNum).map { headerRow.getCell(it)?.toString()?.trim().orEmpty() }
        val index = header.mapIndexed { i, h -> h to i }.toMap()
        fun cell(row: org.apache.poi.ss.usermodel.Row, idx: Int): String {
            if (idx < 0) return ""
            val c = row.getCell(idx) ?: return ""
            return when (c.cellType) {
                org.apache.poi.ss.usermodel.CellType.NUMERIC -> {
                    val v = c.numericCellValue
                    if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
                }
                org.apache.poi.ss.usermodel.CellType.BOOLEAN -> c.booleanCellValue.toString()
                org.apache.poi.ss.usermodel.CellType.STRING -> c.stringCellValue.trim()
                else -> c.toString().trim()
            }
        }
        fun gi(name: String) = index.entries.firstOrNull { it.key.equals(name, true) }?.value ?: -1
        val out = ArrayList<Row>()
        for (r in (sheet.firstRowNum + 1)..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            val arr = (0 until header.size).map { cell(row, it) }.toTypedArray()
            fun get(name: String): String {
                val i = gi(name); return if (i >= 0 && i < arr.size) arr[i] else ""
            }
            out += Row(
                datum = get("Datum"),
                brojPrimke = get("Broj primke"),
                dobavljac = get("Dobavljač"),
                brojUlaznog = get("Broj ulaznog računa"),
                sifraArtikla = get("Šifra artikla"),
                nazivArtikla = get("Naziv artikla"),
                jmj = get("Jmj."),
                kolicina = get("Količina (+)"),
                nabavnaEur = get("Nabavna cijena (EUR)"),
                nabavnaPoKom = get("Nabavna cijena po kom."),
                nabavnaUkupno = get("Nabavna cijena ukupni iznos"),
                nabavnaVrijednost = get("Nabavna vrijednost"),
                extra = header.withIndex().associate { it.value to (arr.getOrNull(it.index) ?: "") }
            )
        }
        wb.close()
        return out
    }
}

private fun buildPdf(cr: ContentResolver, primka: String, rows: List<Row>): Uri? {
    val data = rows.filter { it.brojPrimke == primka }
    if (data.isEmpty()) return null

    val pageW = 842; val pageH = 595
    val pdf = PdfDocument()

    val tf = Typeface.createFromAsset((cr as? android.content.ContextWrapper)?.baseContext?.assets, "fonts/DejaVuSans.ttf")
    val tfBold = Typeface.createFromAsset((cr as? android.content.ContextWrapper)?.baseContext?.assets, "fonts/DejaVuSans-Bold.ttf")

    val title = Paint().apply { isAntiAlias = true; typeface = tfBold; textSize = 16f }
    val small = Paint().apply { isAntiAlias = true; typeface = tf; textSize = 9.5f }
    val smallBold = Paint(small).apply { typeface = tfBold }
    val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f }

    val left = 24f; val right = pageW - 24f; val width = right - left
    val df = DecimalFormat("0.00")

    fun fmtInt(s: String): String {
        val t = s.trim(); val n = t.replace(",", ".").toDoubleOrNull() ?: return t
        return n.toLong().toString()
    }
    fun fmtQty(s: String): String = fmtInt(s)
    fun fmt2(s: String): String {
        val t = s.trim(); val n = t.replace(",", ".").toDoubleOrNull() ?: return t
        return df.format(n)
    }

    fun wrapText(text: String, w: Float, p: Paint): List<String> {
        if (text.isEmpty()) return listOf("")
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        for (wrd in words) {
            val t = if (sb.isEmpty()) wrd else sb.toString() + " " + wrd
            if (p.measureText(t) <= w) { if (sb.isEmpty()) sb.append(wrd) else sb.append(" ").append(wrd) }
            else { if (sb.isNotEmpty()) lines += sb.toString(); sb.clear()
                if (p.measureText(wrd) > w) {
                    var piece = ""
                    for (ch in wrd) {
                        val t2 = piece + ch
                        if (p.measureText(t2) <= w) piece = t2 else { if (piece.isNotEmpty()) lines += piece; piece = ch.toString() }
                    }
                    if (piece.isNotEmpty()) sb.append(piece)
                } else sb.append(wrd)
            }
        }
        if (sb.isNotEmpty()) lines += sb.toString()
        return if (lines.isEmpty()) listOf("") else lines
    }
    fun drawWrapped(canvas: Canvas, text: String, x: Float, y: Float, w: Float, p: Paint, lh: Float) {
        var yy = y; for (ln in wrapText(text, w, p)) { canvas.drawText(ln, x, yy, p); yy += lh }
    }
    fun heightFor(text: String, w: Float, p: Paint, lh: Float) = wrapText(text, w, p).size * lh

    var y = 40f
    fun newPage(): PdfDocument.Page {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pdf.pageCount + 1).create())
        val c = page.canvas
        // Company header (left)
        c.drawText("Metrax d.o.o.", left, y, title); y += 12f
        c.drawText("Ivana Nepomuka Jemeršića 37D", left, y, small); y += 12f
        c.drawText("43290 Grubišno Polje", left, y, small); y += 16f

        // Title center
        val titleText = "PRIMKA $primka"
        val titleW = smallBold.measureText(titleText)
        c.drawText(titleText, (pageW - titleW) / 2f, y, smallBold); y += 14f

        // Header block
        val f = data.first()
        c.drawText("Datum:", left, y, smallBold); c.drawText(f.datum, left + 60f, y, small); y += 12f
        c.drawText("Broj primke:", left, y, smallBold); c.drawText(fmtInt(f.brojPrimke), left + 90f, y, small); y += 12f
        c.drawText("Dobavljač:", left, y, smallBold); drawWrapped(c, f.dobavljac, left + 80f, y, 360f, small, 10f); y += 12f
        c.drawText("Broj ulaznog računa:", left, y, smallBold); drawWrapped(c, f.brojUlaznog, left + 140f, y, 300f, small, 10f); y += 16f

        // Table header
        val cols = listOf("Šifra artikla","Naziv artikla","Jmj./Nab. po kom.","Količina","Nab. ukupno","Nab. vrijednost")
        val weights = listOf(0.09f, 0.40f, 0.17f, 0.08f, 0.13f, 0.13f)
        val colW = weights.map { it * width }
        var x = left; val headH = 20f
        for (i in cols.indices) {
            drawWrapped(c, cols[i], x + 4f, y + 6f, colW[i] - 8f, smallBold, 10f)
            c.drawRect(x, y, x + colW[i], y + headH, line); x += colW[i]
        }
        y += headH
        return page
    }

    var page = newPage(); var canvas = page.canvas
    val rowMinH = 22f

    // Sum for TOTAL (Nabavna vrijednost + opcionalno Nab. ukupno)
    val sumVrijednost = data.sumOf { it.nabavnaVrijednost.replace(",", ".").toDoubleOrNull() ?: 0.0 }
    val sumUkupno = data.sumOf { it.nabavnaUkupno.replace(",", ".").toDoubleOrNull() ?: 0.0 }

    for (r in data) {
        val col2 = "${r.jmj} / ${fmt2(r.nabavnaPoKom)}"
        val vals = listOf(
            fmtInt(r.sifraArtikla),
            r.nazivArtikla,
            col2,
            fmtQty(r.kolicina),
            fmt2(r.nabavnaUkupno),
            fmt2(r.nabavnaVrijednost)
        )
        val colW = listOf(0.09f, 0.40f, 0.17f, 0.08f, 0.13f, 0.13f).map { it * (right - left) }
        val heights = vals.mapIndexed { i, v -> maxOf(rowMinH, heightFor(v, colW[i]-8f, small, 10f) + 8f) }
        val rowH = heights.maxOrNull() ?: rowMinH
        if (y + rowH + 30f > pageH) { pdf.finishPage(page); y = 40f; page = newPage(); canvas = page.canvas }
        var x = left
        for (i in vals.indices) {
            canvas.drawRect(x, y, x + colW[i], y + rowH, line)
            val align = Paint(small).apply { textAlign = if (i in listOf(0,3,4,5)) Paint.Align.RIGHT else Paint.Align.LEFT; typeface = if (i==1) tf else tf }
            val textX = if (i in listOf(0,3,4,5)) x + colW[i] - 4f else x + 4f
            drawWrapped(canvas, vals[i], textX, y + 6f, colW[i]-8f, align, 10f)
            x += colW[i]
        }
        y += rowH
    }

    // TOTAL row (bold text)
    val bold = Paint(small).apply { typeface = tfBold }
    val boldRight = Paint(bold).apply { textAlign = Paint.Align.RIGHT }
    val colW = listOf(0.09f, 0.40f, 0.17f, 0.08f, 0.13f, 0.13f).map { it * (right - left) }
    val totalH = 22f
    if (y + totalH + 20f > pageH) { pdf.finishPage(page); y = 40f; page = newPage(); canvas = page.canvas }
    var x = left
    val totalVals = listOf("", "UKUPNO", "", "", df.format(sumUkupno), df.format(sumVrijednost))
    for (i in totalVals.indices) {
        canvas.drawRect(x, y, x + colW[i], y + totalH, line)
        val p = if (i in listOf(0,3,4,5)) boldRight else bold
        val textX = if (i in listOf(0,3,4,5)) x + colW[i] - 4f else x + 4f
        drawWrapped(canvas, totalVals[i], textX, y + 14f, colW[i]-8f, p, 10f) // single line height effect
        x += colW[i]
    }
    y += totalH

    // Save
    val name = "Primka_${primka}.pdf"
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    else MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
    }
    val item = cr.insert(collection, values) ?: return null
    cr.openOutputStream(item).use { out -> pdf.writeTo(out!!) }
    pdf.close()
    return item
}
