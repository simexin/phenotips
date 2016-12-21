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
package org.phenotips.vocabulary.internal.solr;

import org.phenotips.vocabulary.VocabularyTerm;

import java.io.IOException;
import java.util.Collection;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CommonParams;

import com.google.common.collect.ImmutableMap;
import com.hp.hpl.jena.ontology.OntClass;
import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.ontology.OntModelSpec;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.util.iterator.ExtendedIterator;

/**
 * Ontologies processed from OWL files share much of the processing code.
 *
 * @version $Id$
 * @since 1.3M5
 */
public abstract class AbstractOWLSolrVocabulary extends AbstractSolrVocabulary
{
    static final Boolean DIRECT = true;

    static final String SEPARATOR = ":";

    private static final String VERSION_FIELD_NAME = "version";

    private static final String TERM_GROUP_LABEL = "term_group";

    private static final String HEADER_INFO_LABEL = "HEADER_INFO";

    @Override
    public VocabularyTerm getTerm(@Nullable final String id)
    {
        return StringUtils.isNotBlank(id) ? getTerm(id, super.getTerm(id)) : null;
    }

    /**
     * Returns the result from the first attempt at search if not null, otherwise performs an additional search for
     * the given term ID.
     *
     * @param id the ID of the term of interest
     * @param firstAttempt the result of the first search attempt
     * @return the {@link VocabularyTerm} corresponding with the given ID, null if no such {@link VocabularyTerm} exists
     */
    private VocabularyTerm getTerm(@Nonnull final String id, @Nullable final VocabularyTerm firstAttempt)
    {
        return firstAttempt != null ? firstAttempt : searchTerm(id);
    }

    /**
     * Perform a search for the {@link VocabularyTerm} that corresponds with the given ID.
     *
     * @param id the ID of the term of interest
     * @return the {@link VocabularyTerm} corresponding with the given ID, null if no such {@link VocabularyTerm} exists
     */
    private VocabularyTerm searchTerm(@Nonnull final String id)
    {
        final ImmutableMap.Builder<String, String> queryParamBuilder = ImmutableMap.builder();
        queryParamBuilder.put(ID_FIELD_NAME, id);
        final Collection<VocabularyTerm> results = search(queryParamBuilder.build());
        return CollectionUtils.isNotEmpty(results) ? results.iterator().next() : searchTermWithoutPrefix(id);
    }

    /**
     * If the ID stats with the optional prefix, removes the prefix and performs the search again.
     *
     * @param id the ID of the term of interest
     * @return the {@link VocabularyTerm} corresponding with the given ID, null if no such {@link VocabularyTerm} exists
     */
    private VocabularyTerm searchTermWithoutPrefix(@Nonnull final String id)
    {
        final String optPrefix = this.getTermPrefix() + SEPARATOR;
        return StringUtils.startsWith(id.toUpperCase(), optPrefix.toUpperCase())
            ? getTerm(StringUtils.substringAfter(id, SEPARATOR))
            : null;
    }

    @Override
    protected int index(@Nullable final String sourceUrl)
    {
        final String url = StringUtils.isNotBlank(sourceUrl) ? sourceUrl : getDefaultSourceLocation();
        // Fetch the ontology. If this is over the network, it may take a while.
        final OntModel ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_DL_MEM_TRANS_INF);
        ontModel.read(url);
        // Get the root classes of the ontology that we can start the parsing with.
        final Collection<OntClass> roots = getRootClasses(ontModel);
        // Reusing doc for speed (see http://wiki.apache.org/lucene-java/ImproveIndexingSpeed).
        final SolrInputDocument doc = new SolrInputDocument();
        try {
            // Set the ontology model version.
            setVersion(doc, ontModel);
            // Create and add solr documents for each of the roots.
            for (final OntClass root : roots) {
                // Don't want to add Solr documents for general root categories, so start adding children.
                addChildDocs(doc, root);
            }
            commitDocs();
            return 0;
        } catch (SolrServerException ex) {
            this.logger.warn("Failed to index ontology: {}", ex.getMessage());
        } catch (IOException ex) {
            this.logger.warn("Failed to communicate with the Solr server while indexing ontology: {}", ex.getMessage());
        } catch (OutOfMemoryError ex) {
            this.logger.warn("Failed to add terms to the Solr. Ran out of memory. {}", ex.getMessage());
        }
        return 1;
    }

    /**
     * Create a document for the ontology class, and add it to the index.
     *
     * @param doc the reusable Solr input document
     * @param ontClass the ontology class that should be parsed
     * @param root the top root category for ontClass
     */
    private void addDoc(@Nonnull final SolrInputDocument doc, @Nonnull final OntClass ontClass,
        @Nonnull final OntClass root) throws IOException, SolrServerException
    {
        parseSolrDocumentFromOntClass(doc, ontClass, root);
        parseSolrDocumentFromOntParentClasses(doc, ontClass);
        this.externalServicesAccess.getSolrConnection().add(doc);
        doc.clear();
    }

    /**
     * Adds any of the sub-documents of the specified ontology class.
     * @param doc the reusable Solr input document
     * @param ontClass the ontology class that should be parsed
     */
    private void addChildDocs(@Nonnull final SolrInputDocument doc, @Nonnull final OntClass ontClass)
        throws IOException, SolrServerException
    {
        // Get the direct subclasses of ontClass, and add a Solr document for each of them.
        final ExtendedIterator<OntClass> subClasses = ontClass.listSubClasses();
        int counter = 0;
        while (subClasses.hasNext()) {
            if (counter == getSolrDocsPerBatch()) {
                commitDocs();
                counter = 0;
            }
            final OntClass subClass = subClasses.next();
            addDoc(doc, subClass, ontClass);
            counter++;
        }
        subClasses.close();
    }

    /**
     * Commits the batch of newly-processed documents.
     */
    private void commitDocs() throws IOException, SolrServerException
    {
        this.externalServicesAccess.getSolrConnection().commit();
        this.externalServicesAccess.getTermCache().removeAll();
    }

    @Override
    public String getVersion() {
        final SolrQuery query = new SolrQuery();
        query.setQuery("version:*");
        query.set(CommonParams.ROWS, "1");
        try {
            final QueryResponse response = this.externalServicesAccess.getSolrConnection().query(query);
            final SolrDocumentList termList = response.getResults();

            if (!termList.isEmpty()) {
                final SolrDocument firstDoc = termList.get(0);
                return firstDoc.getFieldValue(VERSION_FIELD_NAME).toString();
            }
        } catch (SolrServerException | SolrException | IOException ex) {
            this.logger.warn("Failed to query ontology version: {}", ex.getMessage());
        }
        return null;
    }

    /**
     * Sets the ontology version data.
     *
     * @param doc the Solr input document
     * @param ontModel the ontology model
     * @throws IOException if failed to communicate with Solr server while indexing ontology
     * @throws SolrServerException if failed to index ontology
     */
    private void setVersion(@Nonnull final SolrInputDocument doc, @Nonnull final OntModel ontModel)
        throws IOException, SolrServerException
    {
        final String version = ontModel.getOntology(getBaseOntologyUri()).getVersionInfo();
        if (StringUtils.isNotBlank(version)) {
            doc.addField(ID_FIELD_NAME, HEADER_INFO_LABEL);
            doc.addField(VERSION_FIELD_NAME, version);
            this.externalServicesAccess.getSolrConnection().add(doc);
            doc.clear();
        }
    }

    /**
     * Creates a Solr document from the provided ontology class.
     *
     * @param doc Solr input document
     * @param ontClass the ontology class
     * @param root the top root category for ontClass
     * @return the Solr input document
     */
    private SolrInputDocument parseSolrDocumentFromOntClass(@Nonnull final SolrInputDocument doc,
        @Nonnull final OntClass ontClass, @Nonnull final OntClass root)
    {
        doc.addField(ID_FIELD_NAME, getFormattedOntClassId(ontClass.getLocalName()));
        doc.addField(TERM_GROUP_LABEL, root.getLabel(null));
        extractProperties(doc, ontClass);
        return doc;
    }

    /**
     * Adds parent data for provided ontology class to the Solr document.
     *
     * @param doc Solr input document
     * @param ontClass the ontology class
     * @return the Solr input document
     */
    private SolrInputDocument parseSolrDocumentFromOntParentClasses(@Nonnull final SolrInputDocument doc,
        @Nonnull final OntClass ontClass)
    {
        // This will list all superclasses for ontClass.
        final ExtendedIterator<OntClass> parents = ontClass.listSuperClasses(!DIRECT);
        while (parents.hasNext()) {
            final OntClass parent = parents.next();
            extractClassData(doc, ontClass, parent);
        }
        parents.close();
        return doc;
    }

    /**
     * Extracts properties from the ontology class, and adds the data to the Solr input document.
     *
     * @param doc the Solr input document
     * @param ontClass the ontology class
     */
    private void extractProperties(@Nonnull final SolrInputDocument doc, @Nonnull final OntClass ontClass)
    {
        final ExtendedIterator<Statement> statements = ontClass.listProperties();
        while (statements.hasNext()) {
            final Statement statement = statements.next();

            final RDFNode object = statement.getObject();
            final String relation = statement.getPredicate().getLocalName();

            extractProperty(doc, relation, object);
        }
        statements.close();
    }

    /**
     * Returns a prefix for the vocabulary term (e.g. ORPHA, HPO).
     *
     * @return the prefix for the vocabulary term, as string
     */
    abstract String getTermPrefix();

    /**
     * Extracts relevant data from the the parent class of ontClass, and writes it to the Solr input document associated
     * with ontClass.
     *
     * @param doc the Solr input document
     * @param ontClass the ontology class of interest
     * @param parent the parent of ontClass
     * @return the Solr input document
     */
    abstract SolrInputDocument extractClassData(@Nonnull final SolrInputDocument doc,
        @Nonnull final OntClass ontClass, @Nonnull final OntClass parent);

    /**
     * Get a numerical id string from a localName. Assuming the localName is in the form "Orphanet_XXX". If localName
     * is an empty string or is null, will return null.
     *
     * @param localName the localName of an OWL class if localName is not null or empty, null otherwise.
     * @return the string id.
     */
    abstract String getFormattedOntClassId(@Nullable final String localName);

    /**
     * Adds the property value to the Solr input document, if it is an item of interest.
     *
     * @param doc the Solr input document
     * @param relation property name
     * @param object the rdf data node
     */
    abstract void extractProperty(@Nonnull final SolrInputDocument doc, @Nonnull final String relation,
        @Nonnull final RDFNode object);

    /**
     * Get a collection of root classes from the provided ontology model.
     *
     * @param ontModel the provided ontology model
     * @return a collection of root classes
     */
    abstract Collection<OntClass> getRootClasses(@Nonnull final OntModel ontModel);

    /**
     * The number of documents to be added and committed to Solr at a time.
     *
     * @return the number of documents as an integer
     */
    abstract int getSolrDocsPerBatch();

    /**
     * Retrieves the base URI for the ontology.
     *
     * @return the base URI of the ontology, as string
     */
    abstract String getBaseOntologyUri();
}
