package org.apache.lucene.search.join;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BitSet;

/**
 * Just like {@link ToParentBlockJoinQuery}, except this
 * query joins in reverse: you provide a Query matching
 * parent documents and it joins down to child
 * documents.
 *
 * @lucene.experimental
 */

public class ToChildBlockJoinQuery extends Query {

  /** Message thrown from {@link
   *  ToChildBlockJoinScorer#validateParentDoc} on mis-use,
   *  when the parent query incorrectly returns child docs. */
  static final String INVALID_QUERY_MESSAGE = "Parent query yields document which is not matched by parents filter, docID=";
  static final String ILLEGAL_ADVANCE_ON_PARENT = "Expect to be advanced on child docs only. got docID=";

  private final BitSetProducer parentsFilter;
  private final Query parentQuery;

  // If we are rewritten, this is the original parentQuery we
  // were passed; we use this for .equals() and
  // .hashCode().  This makes rewritten query equal the
  // original, so that user does not have to .rewrite() their
  // query before searching:
  private final Query origParentQuery;

  /**
   * Create a ToChildBlockJoinQuery.
   * 
   * @param parentQuery Query that matches parent documents
   * @param parentsFilter Filter identifying the parent documents.
   */
  public ToChildBlockJoinQuery(Query parentQuery, BitSetProducer parentsFilter) {
    super();
    this.origParentQuery = parentQuery;
    this.parentQuery = parentQuery;
    this.parentsFilter = parentsFilter;
  }

  private ToChildBlockJoinQuery(Query origParentQuery, Query parentQuery, BitSetProducer parentsFilter) {
    super();
    this.origParentQuery = origParentQuery;
    this.parentQuery = parentQuery;
    this.parentsFilter = parentsFilter;
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, boolean needsScores) throws IOException {
    return new ToChildBlockJoinWeight(this, parentQuery.createWeight(searcher, needsScores), parentsFilter, needsScores);
  }

  /** Return our parent query. */
  public Query getParentQuery() {
    return parentQuery;
  }

  private static class ToChildBlockJoinWeight extends Weight {
    private final Weight parentWeight;
    private final BitSetProducer parentsFilter;
    private final boolean doScores;

    public ToChildBlockJoinWeight(Query joinQuery, Weight parentWeight, BitSetProducer parentsFilter, boolean doScores) {
      super(joinQuery);
      this.parentWeight = parentWeight;
      this.parentsFilter = parentsFilter;
      this.doScores = doScores;
    }

    @Override
    public void extractTerms(Set<Term> terms) {}

    @Override
    public float getValueForNormalization() throws IOException {
      return parentWeight.getValueForNormalization();
    }

    @Override
    public void normalize(float norm, float boost) {
      parentWeight.normalize(norm, boost);
    }

    // NOTE: acceptDocs applies (and is checked) only in the
    // child document space
    @Override
    public Scorer scorer(LeafReaderContext readerContext) throws IOException {

      final Scorer parentScorer = parentWeight.scorer(readerContext);

      if (parentScorer == null) {
        // No matches
        return null;
      }

      // NOTE: this doesn't take acceptDocs into account, the responsibility
      // to not match deleted docs is on the scorer
      final BitSet parents = parentsFilter.getBitSet(readerContext);
      if (parents == null) {
        // No parents
        return null;
      }

      return new ToChildBlockJoinScorer(this, parentScorer, parents, doScores);
    }

    @Override
    public Explanation explain(LeafReaderContext context, int doc) throws IOException {
      ToChildBlockJoinScorer scorer = (ToChildBlockJoinScorer) scorer(context);
      if (scorer != null && scorer.iterator().advance(doc) == doc) {
        int parentDoc = scorer.getParentDoc();
        return Explanation.match(
          scorer.score(), 
          String.format(Locale.ROOT, "Score based on parent document %d", parentDoc + context.docBase), 
          parentWeight.explain(context, parentDoc)
        );
      }
      return Explanation.noMatch("Not a match");
    }
  }

  static class ToChildBlockJoinScorer extends Scorer {
    private final Scorer parentScorer;
    private final DocIdSetIterator parentIt;
    private final BitSet parentBits;
    private final boolean doScores;

    private float parentScore;
    private int parentFreq = 1;

    private int childDoc = -1;
    private int parentDoc = 0;

    public ToChildBlockJoinScorer(Weight weight, Scorer parentScorer, BitSet parentBits, boolean doScores) {
      super(weight);
      this.doScores = doScores;
      this.parentBits = parentBits;
      this.parentScorer = parentScorer;
      this.parentIt = parentScorer.iterator();
    }

    @Override
    public Collection<ChildScorer> getChildren() {
      return Collections.singleton(new ChildScorer(parentScorer, "BLOCK_JOIN"));
    }

    @Override
    public DocIdSetIterator iterator() {
      return new DocIdSetIterator() {

        @Override
        public int docID() {
          return childDoc;
        }

        @Override
        public int nextDoc() throws IOException {
          //System.out.println("Q.nextDoc() parentDoc=" + parentDoc + " childDoc=" + childDoc);

          while (true) {
            if (childDoc+1 == parentDoc) {
              // OK, we are done iterating through all children
              // matching this one parent doc, so we now nextDoc()
              // the parent.  Use a while loop because we may have
              // to skip over some number of parents w/ no
              // children:
              while (true) {
                parentDoc = parentIt.nextDoc();
                validateParentDoc();

                if (parentDoc == 0) {
                  // Degenerate but allowed: first parent doc has no children
                  // TODO: would be nice to pull initial parent
                  // into ctor so we can skip this if... but it's
                  // tricky because scorer must return -1 for
                  // .doc() on init...
                  parentDoc = parentIt.nextDoc();
                  validateParentDoc();
                }

                if (parentDoc == NO_MORE_DOCS) {
                  childDoc = NO_MORE_DOCS;
                  //System.out.println("  END");
                  return childDoc;
                }

                // Go to first child for this next parentDoc:
                childDoc = 1 + parentBits.prevSetBit(parentDoc-1);

                if (childDoc == parentDoc) {
                  // This parent has no children; continue
                  // parent loop so we move to next parent
                  continue;
                }

                if (childDoc < parentDoc) {
                  if (doScores) {
                    parentScore = parentScorer.score();
                    parentFreq = parentScorer.freq();
                  }
                  //System.out.println("  " + childDoc);
                  return childDoc;
                } else {
                  // Degenerate but allowed: parent has no children
                }
              }
            } else {
              assert childDoc < parentDoc: "childDoc=" + childDoc + " parentDoc=" + parentDoc;
              childDoc++;
              //System.out.println("  " + childDoc);
              return childDoc;
            }
          }
        }

        @Override
        public int advance(int childTarget) throws IOException {
          if (childTarget >= parentDoc) {
            if (childTarget == NO_MORE_DOCS) {
              return childDoc = parentDoc = NO_MORE_DOCS;
            }
            parentDoc = parentIt.advance(childTarget + 1);
            validateParentDoc();

            if (parentDoc == NO_MORE_DOCS) {
              return childDoc = NO_MORE_DOCS;
            }

            // scan to the first parent that has children
            while (true) {
              final int firstChild = parentBits.prevSetBit(parentDoc-1) + 1;
              if (firstChild != parentDoc) {
                // this parent has children
                childTarget = Math.max(childTarget, firstChild);
                break;
              }
              // parent with no children, move to the next one
              parentDoc = parentIt.nextDoc();
              validateParentDoc();
              if (parentDoc == NO_MORE_DOCS) {
                return childDoc = NO_MORE_DOCS;
              }
            }

            if (doScores) {
              parentScore = parentScorer.score();
              parentFreq = parentScorer.freq();
            }
          }

          assert childTarget < parentDoc;
          assert !parentBits.get(childTarget);
          childDoc = childTarget;
          //System.out.println("  " + childDoc);
          return childDoc;
        }

        @Override
        public long cost() {
          return parentIt.cost();
        }
      };
    }

    /** Detect mis-use, where provided parent query in fact
     *  sometimes returns child documents.  */
    private void validateParentDoc() {
      if (parentDoc != DocIdSetIterator.NO_MORE_DOCS && !parentBits.get(parentDoc)) {
        throw new IllegalStateException(INVALID_QUERY_MESSAGE + parentDoc);
      }
    }

    @Override
    public int docID() {
      return childDoc;
    }

    @Override
    public float score() throws IOException {
      return parentScore;
    }

    @Override
    public int freq() throws IOException {
      return parentFreq;
    }
    
    int getParentDoc() {
      return parentDoc;
    }
  }

  @Override
  public Query rewrite(IndexReader reader) throws IOException {
    if (getBoost() != 1f) {
      return super.rewrite(reader);
    }
    final Query parentRewrite = parentQuery.rewrite(reader);
    if (parentRewrite != parentQuery) {
      return new ToChildBlockJoinQuery(parentQuery,
                                parentRewrite,
                                parentsFilter);
    } else {
      return super.rewrite(reader);
    }
  }

  @Override
  public String toString(String field) {
    return "ToChildBlockJoinQuery ("+parentQuery.toString()+")";
  }

  @Override
  public boolean equals(Object _other) {
    if (_other instanceof ToChildBlockJoinQuery) {
      final ToChildBlockJoinQuery other = (ToChildBlockJoinQuery) _other;
      return origParentQuery.equals(other.origParentQuery) &&
        parentsFilter.equals(other.parentsFilter) &&
        super.equals(other);
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int hash = super.hashCode();
    hash = prime * hash + origParentQuery.hashCode();
    hash = prime * hash + parentsFilter.hashCode();
    return hash;
  }
}
