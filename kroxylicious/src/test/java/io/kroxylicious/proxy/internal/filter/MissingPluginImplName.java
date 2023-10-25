/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.internal.filter;

import edu.umd.cs.findbugs.annotations.NonNull;

import io.kroxylicious.proxy.filter.Filter;
import io.kroxylicious.proxy.filter.FilterFactory;
import io.kroxylicious.proxy.filter.FilterFactoryContext;
import io.kroxylicious.proxy.plugin.PluginConfigType;
import io.kroxylicious.proxy.plugin.PluginConfigurationException;
import io.kroxylicious.proxy.plugin.PluginImplConfig;

@PluginConfigType(MissingPluginImplName.Config.class)
public class MissingPluginImplName implements FilterFactory<MissingPluginImplName.Config, Void> {

    record Config(
            String id, // This lacks the @PluginImplName annotation
            @PluginImplConfig(implNameProperty = "id") Object config) {}

    @Override
    public Void initialize(FilterFactoryContext context, Config config) throws PluginConfigurationException {
        return null;
    }

    @NonNull
    @Override
    public Filter createFilter(FilterFactoryContext context, Void initializationData) {
        return null;
    }
}
