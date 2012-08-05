/*
 * Copyright 2011 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.walkaround.wave.server.googleimport.conversion;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.walkaround.util.shared.Assert;

import org.waveprotocol.wave.model.document.operation.DocOp;
import org.waveprotocol.wave.model.document.operation.Nindo;
import org.waveprotocol.wave.model.operation.OperationException;
import org.waveprotocol.wave.model.operation.wave.AddParticipant;
import org.waveprotocol.wave.model.operation.wave.BlipContentOperation;
import org.waveprotocol.wave.model.operation.wave.BlipOperationVisitor;
import org.waveprotocol.wave.model.operation.wave.NoOp;
import org.waveprotocol.wave.model.operation.wave.RemoveParticipant;
import org.waveprotocol.wave.model.operation.wave.SubmitBlip;
import org.waveprotocol.wave.model.operation.wave.VersionUpdateOp;
import org.waveprotocol.wave.model.operation.wave.WaveletBlipOperation;
import org.waveprotocol.wave.model.operation.wave.WaveletOperation;
import org.waveprotocol.wave.model.operation.wave.WaveletOperationContext;
import org.waveprotocol.wave.model.operation.wave.WaveletOperationVisitor;
import org.waveprotocol.wave.model.util.Pair;

import java.util.Map;

/**
 * Converts a wavelet history by using a {@link DocumentHistoryConverter} for
 * each document.
 *
 * @author ohler@google.com (Christian Ohler)
 */
public class WaveletHistoryConverter {

  private final Map<String, DocumentHistoryConverter> docConverters = Maps.newHashMap();
  private final Function<Pair<String, Nindo>, Nindo> nindoConverter;

  public WaveletHistoryConverter(Function<Pair<String, Nindo>, Nindo> nindoConverter) {
    this.nindoConverter = checkNotNull(nindoConverter, "Null nindoConverter");
  }

  @Override
  public String toString() {
    return this.getClass().getSimpleName() + "(" + docConverters.size() + " entries in map)";
  }

  public WaveletOperation convertAndApply(final WaveletOperation op) {
    final WaveletOperation[] result = { null };
    op.acceptVisitor(new WaveletOperationVisitor() {
        void setResult(WaveletOperation x) {
          Preconditions.checkState(result[0] == null,
              "%s: More than one result: %s, %s", op, result[0], x);
          result[0] = x;
        }

        @Override public void visitNoOp(NoOp op) {
          setResult(op);
        }

        @Override public void visitVersionUpdateOp(VersionUpdateOp op) {
          throw new AssertionError("Unexpected visitVersionUpdateOp(" + op + ")");
        }

        @Override public void visitAddParticipant(AddParticipant op) {
          setResult(op);
        }

        @Override public void visitRemoveParticipant(RemoveParticipant op) {
          setResult(op);
        }

        @Override public void visitWaveletBlipOperation(final WaveletBlipOperation waveletOp) {
          final String documentId = waveletOp.getBlipId();
          final WaveletOperationContext context = waveletOp.getContext();
          waveletOp.getBlipOp().acceptVisitor(new BlipOperationVisitor() {
              @Override public void visitBlipContentOperation(BlipContentOperation blipOp) {
                DocumentHistoryConverter docConverter = docConverters.get(documentId);
                if (docConverter == null) {
                  docConverter = new DocumentHistoryConverter(documentId, nindoConverter);
                  docConverters.put(documentId, docConverter);
                }
                DocOp converted;
                try {
                  converted = docConverter.convertAndApply(blipOp.getContentOp());
                } catch (OperationException e) {
                  throw new InvalidInputException("OperationException converting " + waveletOp, e);
                }
                setResult(new WaveletBlipOperation(
                    documentId, new BlipContentOperation(context, converted)));
                if (docConverter.getCurrentState().size() == 0) {
                  // HACK(ohler): Save memory.  This is not, strictly speaking,
                  // safe; it assumes that DocumentHistoryConverter and all
                  // nindo converters return to their initial state whenever the
                  // document reaches the empty state.
                  docConverters.remove(documentId);
                }
              }
              @Override public void visitSubmitBlip(SubmitBlip blipOp) {
                throw new AssertionError("Unexpected visitSubmitBlip(" + blipOp + ")");
              }
            });
        }
      });
    Assert.check(result[0] != null, "No result: %s", op);
    return result[0];
  }

}
