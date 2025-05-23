/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical;

import org.elasticsearch.xpack.esql.capabilities.TelemetryAware;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;

import java.util.List;
import java.util.Objects;

public class Keep extends Project implements TelemetryAware, SortAgnostic {

    public Keep(Source source, LogicalPlan child, List<? extends NamedExpression> projections) {
        super(source, child, projections);
    }

    @Override
    protected NodeInfo<Project> info() {
        return NodeInfo.create(this, Keep::new, child(), projections());
    }

    @Override
    public Project replaceChild(LogicalPlan newChild) {
        return new Keep(source(), newChild, projections());
    }

    @Override
    public boolean expressionsResolved() {
        return super.expressionsResolved();
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode());
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj);
    }
}
