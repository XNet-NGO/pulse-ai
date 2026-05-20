package com.xnet.pulse.feature.chat.engine

import android.content.Context
import com.opencsv.CSVWriter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream

object DocumentConverter {

  fun init(ctx: Context) {
    PDFBoxResourceLoader.init(ctx)
  }

  /** Export markdown/text content to DOCX */
  fun toDocx(content: String, output: File) {
    val doc = XWPFDocument()
    content.lines().forEach { line ->
      val para = doc.createParagraph()
      when {
        line.startsWith("# ") -> {
          para.style = "Heading1"
          para.createRun().apply { setText(line.removePrefix("# ")); isBold = true; fontSize = 18 }
        }
        line.startsWith("## ") -> {
          para.style = "Heading2"
          para.createRun().apply { setText(line.removePrefix("## ")); isBold = true; fontSize = 14 }
        }
        line.startsWith("### ") -> {
          para.createRun().apply { setText(line.removePrefix("### ")); isBold = true; fontSize = 12 }
        }
        line.startsWith("- ") || line.startsWith("* ") -> {
          para.createRun().apply { setText("• ${line.drop(2)}") }
        }
        line.isBlank() -> para.createRun().setText("")
        else -> para.createRun().apply { setText(line) }
      }
    }
    FileOutputStream(output).use { doc.write(it) }
    doc.close()
  }

  /** Export content to PDF */
  fun toPdf(content: String, output: File) {
    val doc = PDDocument()
    val font = PDType1Font.HELVETICA
    val boldFont = PDType1Font.HELVETICA_BOLD
    val fontSize = 11f
    val margin = 50f
    val pageWidth = PDRectangle.A4.width - 2 * margin

    var page = PDPage(PDRectangle.A4)
    doc.addPage(page)
    var stream = PDPageContentStream(doc, page)
    var y = PDRectangle.A4.height - margin

    fun newPage() {
      stream.close()
      page = PDPage(PDRectangle.A4)
      doc.addPage(page)
      stream = PDPageContentStream(doc, page)
      y = PDRectangle.A4.height - margin
    }

    content.lines().forEach { line ->
      if (y < margin + 20) newPage()
      val isHeading = line.startsWith("#")
      val text = line.trimStart('#', ' ')
      val f = if (isHeading) boldFont else font
      val size = if (line.startsWith("# ")) 16f else if (line.startsWith("## ")) 13f else fontSize

      // Word wrap
      val words = text.split(" ")
      val lines = mutableListOf<String>()
      var current = StringBuilder()
      for (word in words) {
        val test = if (current.isEmpty()) word else "$current $word"
        val width = f.getStringWidth(test) / 1000 * size
        if (width > pageWidth && current.isNotEmpty()) {
          lines.add(current.toString())
          current = StringBuilder(word)
        } else {
          current = StringBuilder(test)
        }
      }
      if (current.isNotEmpty()) lines.add(current.toString())

      for (l in lines) {
        if (y < margin) newPage()
        stream.beginText()
        stream.setFont(f, size)
        stream.newLineAtOffset(margin, y)
        stream.showText(l)
        stream.endText()
        y -= size + 4
      }
      y -= 4 // paragraph spacing
    }
    stream.close()
    FileOutputStream(output).use { doc.save(it) }
    doc.close()
  }

  /** Export tabular data to XLSX */
  fun toXlsx(content: String, output: File) {
    val wb = XSSFWorkbook()
    val sheet = wb.createSheet("Sheet1")
    content.lines().forEachIndexed { i, line ->
      val row = sheet.createRow(i)
      // Split by | (markdown table) or tab or comma
      val cells = when {
        line.contains("|") -> line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        line.contains("\t") -> line.split("\t")
        else -> line.split(",")
      }
      cells.forEachIndexed { j, cell ->
        row.createCell(j).setCellValue(cell)
      }
    }
    FileOutputStream(output).use { wb.write(it) }
    wb.close()
  }

  /** Export tabular data to CSV */
  fun toCsv(content: String, output: File) {
    val writer = CSVWriter(output.writer())
    content.lines().filter { it.isNotBlank() && !it.matches(Regex("^[\\s|:-]+$")) }.forEach { line ->
      val cells = when {
        line.contains("|") -> line.split("|").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray()
        line.contains("\t") -> line.split("\t").toTypedArray()
        else -> line.split(",").toTypedArray()
      }
      writer.writeNext(cells)
    }
    writer.close()
  }

  /** Read DOCX to text */
  fun readDocx(file: File): String {
    val doc = XWPFDocument(FileInputStream(file))
    val text = doc.paragraphs.joinToString("\n") { it.text }
    doc.close()
    return text
  }

  /** Read XLSX to text */
  fun readXlsx(file: File): String {
    val wb = XSSFWorkbook(FileInputStream(file))
    val sb = StringBuilder()
    for (i in 0 until wb.numberOfSheets) {
      val sheet = wb.getSheetAt(i)
      if (wb.numberOfSheets > 1) sb.appendLine("## ${sheet.sheetName}")
      for (row in sheet) {
        val cells = (0 until row.lastCellNum).map { row.getCell(it)?.toString() ?: "" }
        sb.appendLine(cells.joinToString(" | "))
      }
    }
    wb.close()
    return sb.toString()
  }

  /** Read PDF to text */
  fun readPdf(file: File): String {
    val doc = PDDocument.load(file)
    val text = PDFTextStripper().getText(doc)
    doc.close()
    return text.take(50000)
  }

  /** Read CSV to text */
  fun readCsv(file: File): String {
    val reader = com.opencsv.CSVReader(file.reader())
    val rows = reader.readAll()
    reader.close()
    return rows.joinToString("\n") { it.joinToString(" | ") }
  }
}
