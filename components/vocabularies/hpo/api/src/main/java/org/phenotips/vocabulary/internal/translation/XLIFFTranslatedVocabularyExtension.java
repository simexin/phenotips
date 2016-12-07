/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/
 */
package org.phenotips.vocabulary.internal.translation;

import org.phenotips.vocabulary.MachineTranslator;
import org.phenotips.vocabulary.Vocabulary;
import org.phenotips.vocabulary.VocabularyExtension;
import org.phenotips.vocabulary.VocabularyInputTerm;

import org.xwiki.component.annotation.Component;
import org.xwiki.localization.LocalizationContext;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;

/**
 * Implements {@link VocabularyExtension} to provide translation services. Works with the xml files up at
 * https://github.com/Human-Phenotype-Ontology/HPO-translations
 *
 * @version $Id$
 */
@Component
@Singleton
public class XLIFFTranslatedVocabularyExtension implements VocabularyExtension
{
    /**
     * The format for the file containing the translation - where the first string is the vocabulary name and the second
     * is the language code.
     */
    private static final String TRANSLATION_XML_FORMAT = "%s_%s.xliff";

    /*
     * TODO: I don't like having these field names down here, I'd rather they were somehow set by the vocabulary input
     * term, but that'd require coupling the VocabularyInputTerm to the concept of a language which makes me
     * uncomfortable too
     */

    /**
     * The name field.
     */
    private static final String NAME = "name";

    /**
     * The definition field.
     */
    private static final String DEF = "def";

    /**
     * The format to add a language to a solr field.
     */
    private static final String FIELD_FORMAT = "%s_%s";

    /**
     * A map going from the names of properties in the solr index to the names of properties in the xliff file.
     */
    private static final Map<String, String> PROP_MAP;

    /**
     * The current language. Will be set when we start indexing so that the component supports dynamically switching
     * without restarting phenotips.
     */
    private String lang;

    /**
     * The logger.
     */
    @Inject
    private Logger logger;

    /**
     * The machine translator.
     */
    @Inject
    private MachineTranslator translator;

    /**
     * The deserialized xliff.
     */
    private XLiffFile xliff;

    /**
     * The localization context.
     */
    @Inject
    private LocalizationContext localizationContext;

    /**
     * An xml mapper.
     */
    private XmlMapper mapper = new XmlMapper();

    /**
     * Whether this translation is working at all.
     */
    private boolean enabled;

    static {
        PROP_MAP = new HashMap<>(2);
        PROP_MAP.put(NAME, "label");
        PROP_MAP.put(DEF, "definition");
    }

    @Override
    public boolean isVocabularySupported(Vocabulary vocabulary)
    {
        return "hpo".equals(vocabulary.getIdentifier());
    }

    @Override
    public void indexingStarted(Vocabulary vocabulary)
    {
        this.enabled = false;
        this.lang = this.localizationContext.getCurrentLocale().getLanguage();
        if (shouldMachineTranslate(vocabulary.getIdentifier())) {
            this.translator.loadVocabulary(vocabulary.getIdentifier());
        }
        String xml = String.format(TRANSLATION_XML_FORMAT, vocabulary.getIdentifier(), this.lang);
        try {
            InputStream inStream = this.getClass().getResourceAsStream(xml);
            if (inStream == null) {
                /*
                 * parse will strangely throw a malformed url exception if this is null, which is impossible to
                 * distinguish from an actual malformed url exception, so check here and prevent going forward if
                 * there's no translation
                 */
                this.logger.warn(String.format("Could not find resource %s", xml));
                return;
            }
            this.xliff = this.mapper.readValue(inStream, XLiffFile.class);
            inStream.close();
        } catch (IOException e) {
            this.logger.error("indexingStarted exception " + e.getMessage());
            return;
        }
        /* Everything worked out, enable it */
        this.enabled = true;
    }

    @Override
    public void indexingEnded(Vocabulary vocabulary)
    {
        // This thing holds a huge dictionary inside it, so we don't want java to have any qualms about garbage
        // collecting it.
        this.xliff = null;
        if (shouldMachineTranslate(vocabulary.getIdentifier())) {
            this.translator.unloadVocabulary(vocabulary.getIdentifier());
        }
        this.enabled = false;
    }

    @Override
    public void extendTerm(VocabularyInputTerm term, Vocabulary vocabulary)
    {
        if (!this.enabled) {
            return;
        }
        String id = term.getId();
        String label = this.xliff.getFirstString(id, PROP_MAP.get(NAME));
        String definition = this.xliff.getFirstString(id, PROP_MAP.get(DEF));
        Collection<String> fields = new ArrayList<>(2);
        if (label != null) {
            term.set(String.format(FIELD_FORMAT, NAME, this.lang), label);
        } else {
            /*
             * This is not meant to be the PROP_MAP.get(NAME) because it's the field that the machine translator (not
             * the official HPO xliff sheet) knows this field by, which has no reason not to be the same field that we
             * use.
             */
            fields.add(NAME);
        }
        if (definition != null) {
            term.set(String.format(FIELD_FORMAT, DEF, this.lang), definition);
        } else {
            fields.add(DEF);
        }
        if (shouldMachineTranslate(vocabulary.getIdentifier())) {
            this.translator.translate(vocabulary.getIdentifier(), term, fields);
        }
    }

    /**
     * Return whether we should run the vocabulary given through a machine tranlsator.
     *
     * @param vocabulary the vocabulary
     * @return whether it should be machine translated.
     */
    private boolean shouldMachineTranslate(String vocabulary)
    {
        return this.translator.getSupportedLanguages().contains(this.lang)
            && this.translator.getSupportedVocabularies().contains(vocabulary);
    }
}
