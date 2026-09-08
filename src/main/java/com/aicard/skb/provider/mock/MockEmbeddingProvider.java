package com.aicard.skb.provider.mock;

import com.aicard.skb.provider.EmbeddingProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 可编程 Mock embedding。默认 1536 维 one-hot（与 schema vector(1536) 对齐）。 */
public class MockEmbeddingProvider implements EmbeddingProvider {

    public static final int DIM = 1536;

    private final AtomicReference<List<Float>> vector = new AtomicReference<>(oneHot(0));

    public void setVector(List<Float> v) { vector.set(v); }

    @Override
    public List<Float> embed(String text) { return vector.get(); }

    /** 构造 DIM 维 one-hot 向量（第 hotIndex 维为 1，其余 0）。 */
    public static List<Float> oneHot(int hotIndex) {
        List<Float> v = new ArrayList<>(Collections.nCopies(DIM, 0f));
        v.set(hotIndex, 1f);
        return v;
    }
}
