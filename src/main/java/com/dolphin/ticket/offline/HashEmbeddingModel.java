package com.dolphin.ticket.offline;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 确定性哈希向量 Embedding 桩:offline 演示模式专用,不联网。
 *
 * <p>由文本 hash 派生随机种子生成 64 维单位向量,同一文本恒定同一向量,
 * 保证 SimpleVectorStore 的写入/检索(余弦相似度)离线可复现。
 * 注意:哈希向量无真实语义,相似工单检索结果仅作演示占位。
 */
public class HashEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 64;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>(instructions.size());
        for (int i = 0; i < instructions.size(); i++) {
            embeddings.add(new Embedding(vectorFor(instructions.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private static float[] vectorFor(String text) {
        long seed = text == null ? 0L : (long) text.hashCode() * 1000003L + text.length();
        Random random = new Random(seed);
        float[] vector = new float[DIMENSIONS];
        double norm = 0.0;
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = (float) (random.nextDouble() * 2.0 - 1.0);
            norm += (double) vector[i] * vector[i];
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] /= (float) norm;
        }
        return vector;
    }
}
