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

import org.xwiki.component.annotation.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.solr.common.SolrInputDocument;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.hp.hpl.jena.ontology.IntersectionClass;
import com.hp.hpl.jena.ontology.OntClass;
import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.ontology.Restriction;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.util.iterator.ExtendedIterator;

/**
 * Provides access to the ORPHANET ontology. The ontology prefix is {@code ORPHANET}.
 *
 * @version $Id$
 * @since 1.3M5
 */
@Component
@Named("orphanet")
@Singleton
public class Orphanet extends AbstractOWLSolrVocabulary
{
    private static final String PHENOME_LABEL = "http://www.orpha.net/ORDO/Orphanet_C001";

    private static final String GENETIC_MATERIAL_LABEL = "http://www.orpha.net/ORDO/Orphanet_C010";

    private static final String HASDBXREF_LABEL = "hasDbXref";

    private static final String TERM_CATEGORY_LABEL = "term_category";

    private static final String IS_A_LABEL = "is_a";

    private static final String ON_PROPERTY_LABEL = "onProperty";

    private Set<OntClass> hierarchyRoots;

    @Override
    public String getIdentifier()
    {
        return "orphanet";
    }

    @Override
    public String getName()
    {
        return "Orphanet";
    }

    @Override
    protected String getCoreName()
    {
        return getIdentifier();
    }

    @Override
    public Set<String> getAliases()
    {
        final ImmutableSet.Builder<String> aliasesBuilder = ImmutableSet.builder();
        aliasesBuilder.add(getName());
        aliasesBuilder.add(getIdentifier());
        aliasesBuilder.add(getTermPrefix());
        return aliasesBuilder.build();
    }

    @Override
    int getSolrDocsPerBatch()
    {
        return 15000;
    }

    @Override
    String getBaseOntologyUri()
    {
        return "http://www.orpha.net/ontology/orphanet.owl";
    }

    @Override
    String getTermPrefix()
    {
        return "ORPHA";
    }

    @Override
    public String getDefaultSourceLocation()
    {
        return "http://data.bioontology.org/ontologies/ORDO/submissions/10/download"
            + "?apikey=8b5b7825-538d-40e0-9e9e-5ab9274a9aeb";
    }

    @Override
    public String getWebsite()
    {
        return "http://http://www.orpha.net/";
    }

    @Override
    public String getCitation()
    {
        return "Orphanet: an online database of rare diseases and orphan drugs. Copyright, INSERM 1997. "
            + "Available at http://www.orpha.net.";
    }

    @Override
    Collection<OntClass> getRootClasses(@Nonnull final OntModel ontModel)
    {
        this.hierarchyRoots = ImmutableSet.<OntClass>builder()
            .add(ontModel.getOntClass(PHENOME_LABEL))
            .add(ontModel.getOntClass(GENETIC_MATERIAL_LABEL))
            .build();

        final ImmutableSet.Builder<OntClass> selectedRoots = ImmutableSet.builder();
        for (final OntClass hierarchyRoot : this.hierarchyRoots) {
            selectedRoots.addAll(hierarchyRoot.listSubClasses(DIRECT));
        }
        return selectedRoots.build();
    }

    @Override
    SolrInputDocument extractClassData(@Nonnull final SolrInputDocument doc, @Nonnull final OntClass ontClass,
        @Nonnull final OntClass parent)
    {
        if (parent.isRestriction()) {
            return extractRestrictionData(doc, parent);
        }
        // For Orphanet, an intersection class only contains one or several related restrictions.
        if (parent.isIntersectionClass()) {
            return extractIntersectionData(doc, ontClass, parent);
        }
        // If not a restriction, nor an intersection class, then try to extract as a named class (if not anonymous).
        if (!parent.isAnon()) {
            return extractNamedClassData(doc, ontClass, parent);
        }
        this.logger.warn("Parent class {} of {} is an anonymous class that is neither restriction nor intersection",
            parent.getId(), ontClass.getLocalName());
        return doc;
    }

    /**
     * Extracts parent data iff parent is a subclass of selected hierarchy roots and is not one of the roots.
     *
     * @param doc the Solr input document
     * @param ontClass the ontology class
     * @param parent the parent of the ontology class
     * @return the input Solr document
     */
    private SolrInputDocument extractNamedClassData(@Nonnull final SolrInputDocument doc,
        @Nonnull final OntClass ontClass, @Nonnull final OntClass parent)
    {
        // Note: in Orphanet, a subclass cannot have parents from different top categories (e.g. phenome and geography).
        if (!this.hierarchyRoots.contains(parent) && !hasHierarchyRootAsParent(parent, DIRECT)) {
            // This will not be null, since only anonymous classes have no local name. This check is performed in
            // the calling method (extractClassData).
            final String orphanetId = getFormattedOntClassId(parent.getLocalName());

            // All parents are added to "term_category".
            addField(doc, TERM_CATEGORY_LABEL, orphanetId);

            // If parent is a direct super-class to ontClass, then want to also add the parent to the "is_a" category.
            if (ontClass.hasSuperClass(parent, DIRECT)) {
                addField(doc, IS_A_LABEL, orphanetId);
            }
        }
        return doc;
    }

    /**
     * Extracts data from the intersection-type parent class.
     *
     * @param doc the Solr input document
     * @param parent the parent class that contains the intersection class data for the ontologyClass
     * @return the Solr input document
     */
    private SolrInputDocument extractIntersectionData(@Nonnull final SolrInputDocument doc,
        @Nonnull final OntClass ontClass, @Nonnull final OntClass parent)
    {
        final IntersectionClass intersection = parent.asIntersectionClass();
        final ExtendedIterator<? extends OntClass> operands = intersection.listOperands();

        while (operands.hasNext()) {
            final OntClass operand = operands.next();
            // For Orphanet, there should only be restrictions in intersection classes.
            extractClassData(doc, ontClass, operand);
        }
        operands.close();
        return doc;
    }

    /**
     * Extracts restriction data from the parent class.
     *
     * @param doc the Solr input document
     * @param parent the parent class that contains restriction data for the ontologyClass
     * @return the Solr input document
     */
    private SolrInputDocument extractRestrictionData(@Nonnull final SolrInputDocument doc,
        @Nonnull final OntClass parent)
    {
        final Restriction restriction = parent.asRestriction();

        // Restrictions can be someValuesFrom, hasValue, allValuesFrom, etc. Orphanet appears to only use the first two.
        if (restriction.isSomeValuesFromRestriction()) {
            return extractSomeValuesFromRestriction(doc, restriction);
        }

        if (restriction.isHasValueRestriction()) {
            return extractHasValueRestriction(doc, restriction);
        }

        this.logger.warn("Restriction {} in class {} is neither someValuesFrom nor hasValue type.", restriction.getId(),
            doc.getFieldValue(ID_FIELD_NAME));
        return doc;
    }

    /**
     * Tries to extract a someValuesFrom restriction.
     * @param doc the input Solr document
     * @param restriction the restriction
     * @return the input Solr document
     */
    private SolrInputDocument extractSomeValuesFromRestriction(@Nonnull final SolrInputDocument doc,
        @Nonnull final Restriction restriction)
    {
        // someValuesFrom restrictions refer to the other "modifier" classes such as inheritance, geography, etc.
        // If a disease is part of a group of disorders, it will also be indicated here under a "part_of" property.
        final String fieldName = getOnPropertyFromRestriction(restriction);
        final String fieldValue = getSomeValuesFromRestriction(restriction);

        if (StringUtils.isNotBlank(fieldName) && StringUtils.isNotBlank(fieldValue)) {
            addField(doc, fieldName, fieldValue);
            return doc;
        }
        this.logger.warn("Could not extract data from someValuesFrom restriction {}, onProperty {}, in class {}",
            restriction.getId(), fieldName, doc.getFieldValue(ID_FIELD_NAME));
        return doc;
    }

    /**
     * Tries to extract a hasValue restriction.
     * @param doc the input Solr document
     * @param restriction the restriction
     * @return the input Solr document
     */
    private SolrInputDocument extractHasValueRestriction(@Nonnull final SolrInputDocument doc,
        @Nonnull final Restriction restriction)
    {
        // Not all of these have pretty names. Re-map these via schema.xml field configurations.
        final String fieldName = getOnPropertyFromRestriction(restriction);
        final String fieldValue = restriction.asHasValueRestriction().getHasValue().asLiteral().getLexicalForm();
        if (StringUtils.isNotBlank(fieldName) && StringUtils.isNotBlank(fieldValue)) {
            addField(doc, fieldName, fieldValue);
            return doc;
        }
        this.logger.warn("Could not extract data from hasValue restriction {}, onProperty {}, in class {}",
            restriction.getId(), fieldName, doc.getFieldValue(ID_FIELD_NAME));
        return doc;
    }

    /**
     * A workaround to obtain the label for the onProperty field for a Restriction. Ideally, this should be done by
     * using the {@link Restriction#onProperty(Property)}, however for Orphanet, the stored node cannot be converted
     * into an OntProperty class.
     *
     * @param restriction the restriction being examined
     * @return the onProperty label as a string
     */
    private String getOnPropertyFromRestriction(@Nonnull final Restriction restriction)
    {
        final ExtendedIterator<Statement> statements = restriction.listProperties();
        while (statements.hasNext()) {
            final Statement statement = statements.next();
            // Workaround for getting the property label.
            if (ON_PROPERTY_LABEL.equals(statement.getPredicate().getLocalName())) {
                final String onPropertyLink = statement.getObject().toString();
                return restriction.getOntModel().getOntResource(onPropertyLink).getLabel(null);
            }
        }
        statements.close();
        return null;
    }

    /**
     * Obtains the label for the someValuesFrom restriction.
     *
     * @param restriction the restriction being examined
     * @return the someValuesFrom restriction value as a string
     */
    private String getSomeValuesFromRestriction(@Nonnull final Restriction restriction)
    {
        final OntClass ontClass = restriction.asSomeValuesFromRestriction().getSomeValuesFrom().as(OntClass.class);
        return !hasHierarchyRootAsParent(ontClass, !DIRECT)
            ? ontClass.getLabel(null)
            : getFormattedOntClassId(ontClass.getLocalName());
    }

    /**
     * Returns true iff ontClass has one of the hierarchy roots as a parent.
     *
     * @param ontClass the restriction class
     * @param level specifies the level to search: direct iff true, traverse entire tree otherwise
     * @return true iff the someValuesFrom restriction value should be stored as a name
     */
    private Boolean hasHierarchyRootAsParent(@Nonnull final OntClass ontClass, @Nonnull final Boolean level)
    {
        for (final OntClass hierarcyRoot : this.hierarchyRoots) {
            if (ontClass.hasSuperClass(hierarcyRoot, level)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts DBX reference data.
     *
     * @param doc the Solr input document
     * @param object the {@link RDFNode} object
     */
    private void extractDbxRef(@Nonnull final SolrInputDocument doc, @Nonnull final RDFNode object)
    {
        // If the node is not a literal, will throw a {@link LiteralRequiredException}. For Orphanet, this is always
        // a literal.
        if (object.isLiteral()) {
            final String externalRef = object.asLiteral().getLexicalForm();
            final String ontology = StringUtils.substringBefore(externalRef, SEPARATOR);
            final String externalId = StringUtils.substringAfter(externalRef, SEPARATOR);
            addField(doc, ontology.toLowerCase() + "_id", externalId);
        }
    }

    /**
     * Adds field name and its value to the Solr input document.
     *
     * @param doc the Solr input document
     * @param fieldName the name of the field to be stored
     * @param object the {@link RDFNode} object
     */
    private void extractField(@Nonnull final SolrInputDocument doc, @Nonnull final String fieldName,
        @Nonnull final RDFNode object)
    {
        // If the node is not a literal, will throw a {@link LiteralRequiredException}, so need to check. These will
        // be properties like Class or subClassOf. This kind of data is already added via parents.
        if (object.isLiteral()) {
            final String fieldValue = object.asLiteral().getLexicalForm();
            addField(doc, fieldName, fieldValue);
        }
    }

    /**
     * Adds field value to the Solr input document iff it hasn't already been added.
     *
     * @param doc the Solr input document
     * @param fieldName the name of the field to be added
     * @param fieldValue the value of the field being added
     */
    private void addField(@Nonnull final SolrInputDocument doc, @Nonnull final String fieldName,
        @Nonnull final String fieldValue)
    {
        if (!Optional.fromNullable(doc.getFieldValues(fieldName)).or(Collections.emptyList()).contains(fieldValue)) {
            doc.addField(fieldName, fieldValue);
        }
    }

    @Override
    void extractProperty(@Nonnull final SolrInputDocument doc, @Nonnull final String relation,
        @Nonnull final RDFNode object)
    {
        // hasDBXRef stores references to other databases (e.g. OMIM).
        if (HASDBXREF_LABEL.equals(relation)) {
            extractDbxRef(doc, object);
        } else {
            extractField(doc, relation, object);
        }
    }

    @Override
    String getFormattedOntClassId(@Nullable final String localName)
    {
        return StringUtils.isNotBlank(localName) ? localName.replace("Orphanet_", "") : null;
    }
}
