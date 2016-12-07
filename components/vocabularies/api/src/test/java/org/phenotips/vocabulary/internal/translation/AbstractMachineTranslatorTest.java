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

import org.phenotips.vocabulary.Vocabulary;
import org.phenotips.vocabulary.VocabularyInputTerm;
import org.phenotips.vocabulary.internal.solr.SolrVocabularyInputTerm;

import org.xwiki.environment.Environment;
import org.xwiki.localization.LocalizationContext;
import org.xwiki.test.mockito.MockitoComponentMockingRule;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.apache.solr.common.SolrInputDocument;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the common functionality in the abstract machine translator. Works via a dummy concrete child.
 *
 * @version $Id$
 */
public class AbstractMachineTranslatorTest
{
    /**
     * The name of the vocabulary.
     */
    private static final String VOC_NAME = "dummy";

    private static final String LANG = "es";

    /**
     * The mocker.
     */
    @Rule
    public final MockitoComponentMockingRule<AbstractMachineTranslator> mocker =
        new MockitoComponentMockingRule<AbstractMachineTranslator>(DummyMachineTranslator.class);

    /**
     * The temporary home of our translator.
     */
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * The component under test.
     */
    private AbstractMachineTranslator translator;

    /**
     * A vocabulary input term meant to already exist in the dummy translation file. Check out dummy_dummy_es.xliff to
     * see how it's specified.
     */
    private VocabularyInputTerm term1;

    /**
     * A vocabulary input term that is _not_ in the dummy translation file already.
     */
    private VocabularyInputTerm term2;

    /**
     * The translation fields we care about.
     */
    private Collection<String> fields = new HashSet<>();

    /**
     * A set of synonyms.
     */
    private Set<String> synonyms = new HashSet<>();

    /**
     * The set of "translated" synonyms.
     */
    private Set<String> translatedSynonyms = new HashSet<>();

    /**
     * The added up length of the synonyms.
     */
    private int synonymsLength;

    /**
     * Set up the test.
     */
    @Before
    public void setUp() throws Exception
    {
        Locale locale = new Locale(LANG);
        LocalizationContext ctx = this.mocker.getInstance(LocalizationContext.class);
        when(ctx.getCurrentLocale()).thenReturn(locale);

        Environment environment = this.mocker.getInstance(Environment.class);
        /*
         * Gotta make sure it has a home so we can test that things are properly persisted
         */
        when(environment.getPermanentDirectory()).thenReturn(this.folder.getRoot());

        Vocabulary vocabulary = mock(Vocabulary.class);

        this.synonyms.clear();
        this.translatedSynonyms.clear();
        this.synonyms.add("Newcastle United");
        this.synonyms.add("West Ham");
        this.synonyms.add("Blackburn Rovers");
        for (String synonym : this.synonyms) {
            this.synonymsLength += synonym.length();
            this.translatedSynonyms.add("El " + synonym);
        }

        this.term1 = new SolrVocabularyInputTerm(new SolrInputDocument(), vocabulary);
        this.term1.setId("DUM:0001");
        this.term1.setName("Dummy");
        this.term1.setDescription("Definition");
        this.term1.set("synonym", this.synonyms);

        this.term2 = new SolrVocabularyInputTerm(new SolrInputDocument(), vocabulary);
        this.term2.setId("DUM:0002");
        this.term2.setName("Whatever");
        this.term2.setDescription("Definitions!");
        this.term2.set("synonym", this.synonyms);

        this.term1 = spy(this.term1);
        this.term2 = spy(this.term2);

        this.fields.clear();
        this.fields.add("name");

        this.translator = spy(this.mocker.getComponentUnderTest());
        this.translator.loadVocabulary(VOC_NAME, LANG);
    }

    @After
    public void tearDown()
    {
        this.translator.unloadVocabulary(VOC_NAME, LANG);
    }

    /**
     * Test that the machine translator will not retranslate something that's already in the file.
     */
    @Test
    public void testNoReTranslate()
    {
        long count = this.translator.translate(VOC_NAME, LANG, this.term1, this.fields);
        assertEquals(0, count);
        verify(this.translator, never()).doTranslate(this.term1.getName(), LANG);
        verify(this.term1).set("name_es", "El Dummy");
        verify(this.term1, never()).set(eq("name"), any(String.class));
    }

    /**
     * Test that the machine translator will translate when necessary.
     */
    @Test
    public void testDoTranslate()
    {
        long count = this.translator.translate(VOC_NAME, LANG, this.term2, this.fields);
        assertEquals(this.term2.getName().length(), count);
        verify(this.translator).doTranslate(this.term2.getName(), LANG);
        verify(this.term2).set("name_es", "El Whatever");
        verify(this.term2, never()).set(eq("name"), any(String.class));
    }

    /**
     * Test that previously performed translations will be cached.
     */
    @Test
    public void testRemember()
    {
        this.translator.translate(VOC_NAME, LANG, this.term2, this.fields);
        long count = this.translator.translate(VOC_NAME, LANG, this.term2, this.fields);
        assertEquals(0, count);
        verify(this.translator, times(1)).doTranslate(this.term2.getName(), LANG);
        verify(this.term2, times(2)).set("name_es", "El Whatever");
        verify(this.term2, never()).set(eq("name"), any(String.class));
    }

    /**
     * Test that we can't translate unless loadVocabulary has been invoked.
     */
    @Test
    public void testNotWhenUnloaded()
    {
        this.translator.unloadVocabulary(VOC_NAME, LANG);
        try {
            this.translator.translate(VOC_NAME, LANG, this.term1, this.fields);
            fail("Did not throw on translate when unloaded");
        } catch (IllegalStateException e) {
            /* So tearDown doesn't fail */
            this.translator.loadVocabulary(VOC_NAME, LANG);
        }
    }

    /**
     * Test that we can cope with new fields.
     */
    @Test
    public void testNewField()
    {
        this.fields.add("def");
        long count = this.translator.translate(VOC_NAME, LANG, this.term1, this.fields);
        assertEquals(this.term1.getDescription().length(), count);
        verify(this.term1).set("def_es", "El " + this.term1.getDescription());
        verify(this.term1, never()).set(eq("def"), any(String.class));
        verify(this.translator).doTranslate(this.term1.getDescription(), LANG);
    }

    /**
     * Test that previously performed translations are remembered accross restarts of the component.
     */
    @Test
    public void testPersist()
    {
        this.translator.translate(VOC_NAME, LANG, this.term2, this.fields);
        this.translator.unloadVocabulary(VOC_NAME, LANG);
        this.translator.loadVocabulary(VOC_NAME, LANG);
        long count = this.translator.translate(VOC_NAME, LANG, this.term2, this.fields);
        assertEquals(0, count);
        verify(this.translator, times(1)).doTranslate(this.term2.getName(), LANG);
        verify(this.term2, times(2)).set("name_es", "El Whatever");
        verify(this.term2, never()).set(eq("name"), any(String.class));
    }

    /**
     * Test that a newly added field (to an already existing term) will have its translation persisted.
     */
    @Test
    public void testNewFieldPersisted()
    {
        this.fields.add("def");
        this.translator.translate(VOC_NAME, LANG, this.term1, this.fields);
        this.translator.unloadVocabulary(VOC_NAME, LANG);
        this.translator.loadVocabulary(VOC_NAME, LANG);
        long count = this.translator.translate(VOC_NAME, LANG, this.term1, this.fields);
        assertEquals(0, count);
        /* One time for each translation */
        verify(this.term1, times(2)).set("def_es", "El Definition");
        /* Only once: for the first translation */
        verify(this.translator, times(1)).doTranslate(this.term1.getDescription(), LANG);
        verify(this.term1, never()).set(eq("def"), any(String.class));
    }

    /*
     * FIXME These are very tightly coupled to the current implementation's practice of using append() when dynamically
     * translating and set() when just reading. That's not good.
     */

    /**
     * Test that multivalued terms can be read from the translation.
     */
    @Test
    public void testReadMultiValued()
    {
        this.fields.clear();
        this.fields.add("synonym");
        long count = this.translator.translate(VOC_NAME, LANG, this.term1, this.fields);
        assertEquals(0, count);
        verify(this.term1).set(eq("synonym_es"), argThat(containsInAnyOrder(this.translatedSynonyms.toArray())));
        verify(this.term1, never()).set(eq("synonym"), any(Set.class));
        verify(this.translator, never()).doTranslate(any(String.class), any(String.class));
    }

    /**
     * Test that multivalued terms can be translated at all.
     */
    @Test
    public void testTranslateMultiValued()
    {
        this.fields.clear();
        this.fields.add("synonym");
        long count = this.translator.translate(VOC_NAME, LANG, this.term2, this.fields);
        assertEquals(this.synonymsLength, count);
        verify(this.term2, never()).set(eq("synonym"), any(Set.class));
        verify(this.translator, times(this.synonyms.size())).doTranslate(any(String.class), any(String.class));
        for (String synonym : this.synonyms) {
            verify(this.translator).doTranslate(eq(synonym), eq(LANG));
        }
        for (String synonym : this.translatedSynonyms) {
            verify(this.term2).append(eq("synonym_es"), eq(synonym));
        }
    }

    /**
     * Test that multivalued fields get properly persisted.
     */
    @Test
    public void testPersistMultiValued()
    {
        this.fields.clear();
        this.fields.add("synonym");
        this.translator.translate(VOC_NAME, LANG, this.term2, this.fields);
        this.translator.unloadVocabulary(VOC_NAME, LANG);
        this.translator.loadVocabulary(VOC_NAME, LANG);
        long count = this.translator.translate(VOC_NAME, LANG, this.term2, this.fields);
        assertEquals(0, count);
        verify(this.term2, never()).set(eq("synonym"), any(String.class));
        verify(this.translator, times(this.synonyms.size())).doTranslate(any(String.class), any(String.class));
        for (String synonym : this.synonyms) {
            verify(this.translator, times(1)).doTranslate(eq(synonym), eq(LANG));
        }
        for (String synonym : this.translatedSynonyms) {
            verify(this.term2).append(eq("synonym_es"), eq(synonym));
        }
        verify(this.term2).set(eq("synonym_es"),
            argThat(containsInAnyOrder(this.translatedSynonyms.toArray())));
    }

    @Test
    public void testCount()
    {
        this.fields.add("synonym");
        this.fields.add("def");
        long count = this.translator.getMissingCharacters(VOC_NAME, LANG, this.term2, this.fields);
        assertEquals(this.term2.getName().length() + this.term2.getDescription().length() + this.synonymsLength,
            count);
        verify(this.term2, never()).set(any(String.class), any(Object.class));
    }

    /**
     * Provides a dummy implementation of machine translator methods. Translates terms into Spanish following the
     * cartoonish principle of prepending the definite article "El " to the word.
     *
     * @version $Id$
     */
    public static class DummyMachineTranslator extends AbstractMachineTranslator
    {
        @Override
        public Collection<String> getSupportedLanguages()
        {
            Collection<String> retval = new HashSet<>(1);
            retval.add(LANG);
            return retval;
        }

        @Override
        public Collection<String> getSupportedVocabularies()
        {
            Collection<String> retval = new HashSet<>(1);
            retval.add(VOC_NAME);
            return retval;
        }

        @Override
        public String getIdentifier()
        {
            return "dummy";
        }

        @Override
        protected String doTranslate(String msg, String lang)
        {
            return "El " + msg;
        }
    }
}
