package eu.modernmt.model.corpus.impl.tmx;

import eu.modernmt.model.corpus.BilingualCorpus;
import eu.modernmt.xml.XMLUtils;
import org.apache.commons.io.IOUtils;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Created by davide on 01/12/16.
 */
class TMXBilingualLineWriter implements BilingualCorpus.BilingualLineWriter {

    private final SimpleDateFormat dateFormat = new SimpleDateFormat(TMXCorpus.TMX_DATE_FORMAT);

    private final File tmx;
    private final String sourceLanguage;
    private final String targetLanguage;

    private final FileOutputStream stream;
    private final XMLStreamWriter writer;

    public TMXBilingualLineWriter(File tmx, Locale sourceLanguage, Locale targetLanguage) throws IOException {
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));

        this.tmx = tmx;
        this.sourceLanguage = sourceLanguage.toLanguageTag();
        this.targetLanguage = targetLanguage.toLanguageTag();

        FileOutputStream stream = null;
        XMLStreamWriter writer = null;

        try {
            stream = new FileOutputStream(tmx, false);
            writer = XMLUtils.createStreamWriter(stream);
        } catch (XMLStreamException e) {
            throw new IOException("Error while creating XMLStreamWriter for TMX " + tmx, e);
        } finally {
            if (writer == null)
                IOUtils.closeQuietly(stream);
        }

        this.stream = stream;
        this.writer = writer;

        this.writeHeader();
    }

    @Override
    public void write(String source, String target) throws IOException {
        write(new BilingualCorpus.StringPair(source, target));
    }

    @Override
    public void write(BilingualCorpus.StringPair pair) throws IOException {
        try {
            writer.writeStartElement("tu");
            writer.writeAttribute("srclang", sourceLanguage);
            writer.writeAttribute("datatype", "plaintext");

            if (pair.timestamp != null)
                writer.writeAttribute("creationdate", dateFormat.format(pair.timestamp));

            writer.writeStartElement("tuv");
            writer.writeAttribute("xml", TMXCorpus.XML_NAMESPACE, "lang", sourceLanguage);
            writer.writeStartElement("seg");
            writer.writeCharacters(pair.source);
            writer.writeEndElement();
            writer.writeEndElement();

            writer.writeStartElement("tuv");
            writer.writeAttribute("xml", TMXCorpus.XML_NAMESPACE, "lang", targetLanguage);
            writer.writeStartElement("seg");
            writer.writeCharacters(pair.target);
            writer.writeEndElement();
            writer.writeEndElement();

            writer.writeEndElement();
        } catch (XMLStreamException e) {
            throw new IOException("Error while writing XMLStreamWriter", e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            this.flush();
            writer.close();
        } catch (XMLStreamException e) {
            throw new IOException("Error while closing XMLStreamWriter", e);
        } finally {
            IOUtils.closeQuietly(stream);
        }
    }

    private void writeHeader() throws IOException {
        try {
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("tmx");
            writer.writeAttribute("version", "1.4");

            writer.writeEmptyElement("header");
            writer.writeAttribute("creationtool", "ModernMT - modernmt.eu");
            writer.writeAttribute("creationtoolversion", "1.0");
            writer.writeAttribute("datatype", "plaintext");
            writer.writeAttribute("o-tmf", "ModernMT");
            writer.writeAttribute("segtype", "sentence");
            writer.writeAttribute("adminlang", "en-us");
            writer.writeAttribute("srclang", sourceLanguage);

            writer.writeStartElement("body");
        } catch (XMLStreamException e) {
            throw new IOException("Error while writing to TMX " + tmx, e);
        }
    }

    private void flush() throws XMLStreamException {
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndDocument();
        writer.flush();
    }

}
