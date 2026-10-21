package com.smartrag.repository;

import com.smartrag.model.ChunkInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChunkInfoRepository extends JpaRepository<ChunkInfo, Long> {
    List<ChunkInfo> findByFileMd5OrderByChunkIndexAsc(String fileMd5);

    boolean existsByFileMd5AndChunkIndex(String fileMd5, int chunkIndex);

    @Transactional
    @Modifying
    @Query("delete from ChunkInfo c where c.fileMd5 = :fileMd5")
    int deleteByFileMd5(@Param("fileMd5") String fileMd5);

    @Transactional
    @Modifying
    @Query("delete from ChunkInfo c where c.fileMd5 = :fileMd5 and c.chunkIndex = :chunkIndex")
    int deleteByFileMd5AndChunkIndex(@Param("fileMd5") String fileMd5, @Param("chunkIndex") int chunkIndex);

    @Query("select c.chunkIndex from ChunkInfo c where c.fileMd5 = :fileMd5 order by c.chunkIndex asc")
    List<Integer> findChunkIndexesByFileMd5(@Param("fileMd5") String fileMd5);
}
