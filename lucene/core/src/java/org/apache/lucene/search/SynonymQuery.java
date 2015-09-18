package org.apache.lucene.search;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermContext;
import org.apache.lucene.index.TermState;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.search.similarities.Similarity.SimScorer;

/**
 * A query that treats multiple terms as synonyms.
 * <p>
 * For scoring purposes, this query tries to score the terms as if you
 * had indexed them as one term: it will match any of the terms but
 * only invoke the similarity a single time, scoring the sum of all
 * term frequencies for the document.
 */
public final class SynonymQuery extends Query {
  private final Term terms[];
  
  /**
   * Creates a new SynonymQuery, matching any of the supplied terms.
   * <p>
   * The terms must all have the same field.
   */
  public SynonymQuery(Term... terms) {
    this.terms = Objects.requireNonNull(terms).clone();
    // check that all terms are the same field
    String field = null;
    for (Term term : terms) {
      if (field == null) {
        field = term.field();
      } else if (!term.field().equals(field)) {
        throw new IllegalArgumentException("Synonyms must be across the same field");
      }
    }
    if (terms.length > BooleanQuery.getMaxClauseCount()) {
      throw new BooleanQuery.TooManyClauses();
    }
    Arrays.sort(this.terms);
  }

  @Override
  public String toString(String field) {
    StringBuilder builder = new StringBuilder("Synonym(");
    for (int i = 0; i < terms.length; i++) {
      if (i != 0) {
        builder.append(" ");
      }
      Query termQuery = new TermQuery(terms[i]);
      builder.append(termQuery.toString(field));
    }
    builder.append(")");
    return builder.toString();
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = super.hashCode();
    result = prime * result + Arrays.hashCode(terms);
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (!super.equals(obj)) return false;
    if (getClass() != obj.getClass()) return false;
    SynonymQuery other = (SynonymQuery) obj;
    if (!Arrays.equals(terms, other.terms)) return false;
    return true;
  }

  @Override
  public Query rewrite(IndexReader reader) throws IOException {
    // optimize zero and single term cases
    if (terms.length == 0) {
      return new BooleanQuery.Builder().build();
    }
    if (terms.length == 1) {
      return new TermQuery(terms[0]);
    }
    return this;
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, boolean needsScores) throws IOException {
    if (needsScores) {
      return new SynonymWeight(this, searcher);
    } else {
      // if scores are not needed, let BooleanWeight deal with optimizing that case.
      BooleanQuery.Builder bq = new BooleanQuery.Builder();
      for (Term term : terms) {
        bq.add(new TermQuery(term), BooleanClause.Occur.SHOULD);
      }
      return searcher.rewrite(bq.build()).createWeight(searcher, needsScores);
    }
  }
  
  class SynonymWeight extends Weight {
    private final TermContext termContexts[];
    private final Similarity similarity;
    private final Similarity.SimWeight simWeight;

    SynonymWeight(Query query, IndexSearcher searcher) throws IOException {
      super(query);
      CollectionStatistics collectionStats = searcher.collectionStatistics(terms[0].field());
      long docFreq = 0;
      long totalTermFreq = 0;
      termContexts = new TermContext[terms.length];
      for (int i = 0; i < termContexts.length; i++) {
        termContexts[i] = TermContext.build(searcher.getTopReaderContext(), terms[i]);
        TermStatistics termStats = searcher.termStatistics(terms[i], termContexts[i]);
        docFreq = Math.max(termStats.docFreq(), docFreq);
        if (termStats.totalTermFreq() == -1) {
          totalTermFreq = -1;
        } else if (totalTermFreq != -1) {
          totalTermFreq += termStats.totalTermFreq();
        }
      }
      TermStatistics pseudoStats = new TermStatistics(null, docFreq, totalTermFreq);
      this.similarity = searcher.getSimilarity(true);
      this.simWeight = similarity.computeWeight(collectionStats, pseudoStats);
    }

    @Override
    public void extractTerms(Set<Term> terms) {
      for (Term term : SynonymQuery.this.terms) {
        terms.add(term);
      }
    }

    @Override
    public Explanation explain(LeafReaderContext context, int doc) throws IOException {
      Scorer scorer = scorer(context);
      if (scorer != null) {
        int newDoc = scorer.advance(doc);
        if (newDoc == doc) {
          final float freq;
          if (scorer instanceof SynonymScorer) {
            SynonymScorer synScorer = (SynonymScorer) scorer;
            freq = synScorer.tf(synScorer.subScorers.topList());
          } else {
            assert scorer instanceof TermScorer;
            freq = scorer.freq();
          }
          SimScorer docScorer = similarity.simScorer(simWeight, context);
          Explanation freqExplanation = Explanation.match(freq, "termFreq=" + freq);
          Explanation scoreExplanation = docScorer.explain(doc, freqExplanation);
          return Explanation.match(
              scoreExplanation.getValue(),
              "weight(" + getQuery() + " in " + doc + ") ["
                  + similarity.getClass().getSimpleName() + "], result of:",
              scoreExplanation);
        }
      }
      return Explanation.noMatch("no matching term");
    }

    @Override
    public float getValueForNormalization() throws IOException {
      return simWeight.getValueForNormalization();
    }

    @Override
    public void normalize(float norm, float boost) {
      simWeight.normalize(norm, boost);
    }

    @Override
    public Scorer scorer(LeafReaderContext context) throws IOException {
      Similarity.SimScorer simScorer = similarity.simScorer(simWeight, context);
      // we use termscorers + disjunction as an impl detail
      List<Scorer> subScorers = new ArrayList<>();
      for (int i = 0; i < terms.length; i++) {
        TermState state = termContexts[i].get(context.ord);
        if (state != null) {
          TermsEnum termsEnum = context.reader().terms(terms[i].field()).iterator();
          termsEnum.seekExact(terms[i].bytes(), state);
          PostingsEnum postings = termsEnum.postings(null, PostingsEnum.FREQS);
          subScorers.add(new TermScorer(this, postings, simScorer));
        }
      }
      if (subScorers.isEmpty()) {
        return null;
      } else if (subScorers.size() == 1) {
        // we must optimize this case (term not in segment), disjunctionscorer requires >= 2 subs
        return subScorers.get(0);
      } else {
        return new SynonymScorer(simScorer, this, subScorers);
      }
    }
  }
  
  static class SynonymScorer extends DisjunctionScorer {
    private final Similarity.SimScorer similarity;
    
    SynonymScorer(Similarity.SimScorer similarity, Weight weight, List<Scorer> subScorers) {
      super(weight, subScorers, true);
      this.similarity = similarity;
    }

    @Override
    protected float score(DisiWrapper<Scorer> topList) throws IOException {
      return similarity.score(topList.doc, tf(topList));
    }
    
    /** combines TF of all subs. */
    final int tf(DisiWrapper<Scorer> topList) throws IOException {
      int tf = 0;
      for (DisiWrapper<Scorer> w = topList; w != null; w = w.next) {
        tf += w.iterator.freq();
      }
      return tf;
    }
  }
}
