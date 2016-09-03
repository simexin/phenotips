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
package org.phenotips.configuration.internal;

import org.phenotips.components.ComponentManagerRegistry;
import org.phenotips.configuration.RecordConfiguration;
import org.phenotips.configuration.RecordElement;
import org.phenotips.configuration.RecordSection;

import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReference;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import javax.inject.Provider;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Default (global) implementation of the {@link RecordConfiguration} role.
 *
 * @version $Id$
 * @since 1.0M9
 */
public class DefaultRecordConfiguration implements RecordConfiguration
{
    /** The location where preferences are stored. */
    private static final EntityReference PREFERENCES_LOCATION = new EntityReference("WebHome", EntityType.DOCUMENT,
        new EntityReference("data", EntityType.SPACE));

    /** Provides access to the current request context. */
    protected Provider<XWikiContext> xcontextProvider;

    /** Logging helper object. */
    private Logger logger = LoggerFactory.getLogger(DefaultRecordConfiguration.class);

    /** List of all Record Sections. */
    private List<RecordSection> sections;

    @Override
    public List<RecordSection> getAllSections()
    {
        return Collections.unmodifiableList(this.sections);
    }

    @Override
    public void setSections(List<RecordSection> sections)
    {
        this.sections = sections;
    }

    @Override
    public List<RecordSection> getEnabledSections()
    {
        List<RecordSection> result = new LinkedList<>();
        for (RecordSection section : getAllSections()) {
            if (section.isEnabled()) {
                result.add(section);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<String> getEnabledFieldNames()
    {
        List<String> result = new LinkedList<>();
        for (RecordSection section : getEnabledSections()) {
            for (RecordElement element : section.getEnabledElements()) {
                result.addAll(element.getDisplayedFields());
            }
        }
        return Collections.unmodifiableList(result);
    }

    @SuppressWarnings("deprecation")
    @Override
    public List<String> getEnabledNonIdentifiableFieldNames()
    {
        List<String> result = new LinkedList<>();
        for (RecordSection section : getEnabledSections()) {
            for (RecordElement element : section.getEnabledElements()) {
                if (!element.containsPrivateIdentifiableInformation()) {
                    result.addAll(element.getDisplayedFields());
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public DocumentReference getPhenotypeMapping()
    {
        try {
            String mapping = "PhenoTips.PhenotypeMapping";
            BaseObject settings = getGlobalConfigurationObject();
            mapping = StringUtils.defaultIfBlank(settings.getStringValue("phenotypeMapping"), mapping);
            DocumentReferenceResolver<String> resolver = ComponentManagerRegistry.getContextComponentManager()
                .getInstance(DocumentReferenceResolver.TYPE_STRING, "current");
            return resolver.resolve(mapping);
        } catch (NullPointerException ex) {
            // No value set, return the default
        } catch (ComponentLookupException e) {
            // Shouldn't happen, base components must be available
        }
        return null;
    }

    @Override
    public String getISODateFormat()
    {
        return "yyyy-MM-dd";
    }

    @Override
    public String getDateOfBirthFormat()
    {
        String result = getISODateFormat();
        try {
            BaseObject settings = getGlobalConfigurationObject();
            result = StringUtils.defaultIfBlank(settings.getStringValue("dateOfBirthFormat"), result);
        } catch (NullPointerException ex) {
            // No value set, return the default
        }
        return result;
    }

    @Override
    public String toString()
    {
        return StringUtils.join(getEnabledSections(), ", ");
    }

    private BaseObject getGlobalConfigurationObject()
    {
        try {
            XWikiContext context = this.xcontextProvider.get();
            return context.getWiki().getDocument(PREFERENCES_LOCATION, context).getXObject(GLOBAL_PREFERENCES_CLASS);
        } catch (XWikiException ex) {
            this.logger.warn("Failed to read preferences: {}", ex.getMessage());
        }
        return null;
    }
}
