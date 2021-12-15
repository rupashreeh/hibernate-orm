/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.community.dialect;

import java.util.List;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.ComparisonOperator;
import org.hibernate.query.IllegalQueryOperationException;
import org.hibernate.query.SemanticException;
import org.hibernate.sql.ast.SqlAstJoinType;
import org.hibernate.sql.ast.spi.AbstractSqlAstTranslator;
import org.hibernate.sql.ast.spi.SqlSelection;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.ast.tree.cte.CteStatement;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.Literal;
import org.hibernate.sql.ast.tree.expression.SqlTuple;
import org.hibernate.sql.ast.tree.expression.Summarization;
import org.hibernate.sql.ast.tree.from.TableGroupJoin;
import org.hibernate.sql.ast.tree.predicate.Junction;
import org.hibernate.sql.ast.tree.predicate.Predicate;
import org.hibernate.sql.ast.tree.select.QueryGroup;
import org.hibernate.sql.ast.tree.select.QueryPart;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.ast.tree.select.SelectClause;
import org.hibernate.sql.exec.spi.JdbcOperation;

/**
 * A SQL AST translator for TimesTen.
 *
 * @author Christian Beikov
 */
public class TimesTenSqlAstTranslator<T extends JdbcOperation> extends AbstractSqlAstTranslator<T> {

	public TimesTenSqlAstTranslator(SessionFactoryImplementor sessionFactory, Statement statement) {
		super( sessionFactory, statement );
	}

	@Override
	protected LockStrategy determineLockingStrategy(
			QuerySpec querySpec,
			ForUpdateClause forUpdateClause,
			Boolean followOnLocking) {
		// TimesTen supports locks with aggregates but not with set operators
		// See https://docs.oracle.com/cd/E11882_01/timesten.112/e21642/state.htm#TTSQL329
		LockStrategy strategy = LockStrategy.CLAUSE;
		if ( getQueryPartStack().findCurrentFirst( part -> part instanceof QueryGroup ? part : null ) != null ) {
			if ( Boolean.FALSE.equals( followOnLocking ) ) {
				throw new IllegalQueryOperationException( "Locking with set operators is not supported!" );
			}
			strategy = LockStrategy.FOLLOW_ON;
		}
		return strategy;
	}

	@Override
	protected void renderTableGroupJoin(TableGroupJoin tableGroupJoin, List<TableGroupJoin> tableGroupJoinCollector) {
		if ( tableGroupJoin.getJoinType() == SqlAstJoinType.CROSS ) {
			appendSql( ", " );
		}
		else {
			appendSql( WHITESPACE );
			appendSql( tableGroupJoin.getJoinType().getText() );
			appendSql( "join " );
		}

		final Predicate predicate;
		if ( tableGroupJoin.isLateral() ) {
			append( "lateral " );
			if ( tableGroupJoin.getPredicate() == null ) {
				predicate = new Junction( Junction.Nature.CONJUNCTION );
			}
			else {
				predicate = tableGroupJoin.getPredicate();
			}
		}
		else {
			predicate = tableGroupJoin.getPredicate();
		}
		if ( predicate != null && !predicate.isEmpty() ) {
			renderTableGroup( tableGroupJoin.getJoinedGroup(), predicate, tableGroupJoinCollector );
		}
		else {
			renderTableGroup( tableGroupJoin.getJoinedGroup(), tableGroupJoinCollector );
		}
	}

	@Override
	protected void renderSearchClause(CteStatement cte) {
		// TimesTen does not support this, but it's just a hint anyway
	}

	@Override
	protected void renderCycleClause(CteStatement cte) {
		// TimesTen does not support this, but it can be emulated
	}

	@Override
	protected void visitSqlSelections(SelectClause selectClause) {
		renderRowsToClause( (QuerySpec) getQueryPartStack().getCurrent() );
		super.visitSqlSelections( selectClause );
	}

	@Override
	protected void renderFetchPlusOffsetExpression(
			Expression fetchClauseExpression,
			Expression offsetClauseExpression,
			int offset) {
		renderFetchPlusOffsetExpressionAsSingleParameter( fetchClauseExpression, offsetClauseExpression, offset );
	}

	@Override
	public void visitOffsetFetchClause(QueryPart queryPart) {
		// TimesTen uses ROWS TO clause
	}

	@Override
	protected void renderComparison(Expression lhs, ComparisonOperator operator, Expression rhs) {
		renderComparisonEmulateIntersect( lhs, operator, rhs );
	}

	@Override
	protected void renderSelectTupleComparison(
			List<SqlSelection> lhsExpressions,
			SqlTuple tuple,
			ComparisonOperator operator) {
		emulateTupleComparison( lhsExpressions, tuple.getExpressions(), operator, true );
	}

	@Override
	protected void renderPartitionItem(Expression expression) {
		if ( expression instanceof Literal ) {
			appendSql( "'0' || '0'" );
		}
		else if ( expression instanceof Summarization ) {
			// This could theoretically be emulated by rendering all grouping variations of the query and
			// connect them via union all but that's probably pretty inefficient and would have to happen
			// on the query spec level
			throw new UnsupportedOperationException( "Summarization is not supported by DBMS!" );
		}
		else {
			expression.accept( this );
		}
	}

	@Override
	protected boolean supportsRowValueConstructorSyntax() {
		return false;
	}

	@Override
	protected boolean supportsRowValueConstructorSyntaxInInList() {
		return false;
	}

	@Override
	protected boolean supportsRowValueConstructorSyntaxInQuantifiedPredicates() {
		return false;
	}
}
