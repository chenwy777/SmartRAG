package com.smartrag.service;

import com.smartrag.config.MinioConfig;
import com.smartrag.exception.CustomException;
import com.smartrag.model.ChunkInfo;
import com.smartrag.model.FileUpload;
import com.smartrag.repository.ChunkInfoRepository;
import com.smartrag.repository.FileUploadRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UploadServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private MinioClient minioClient;

    @Mock
    private FileUploadRepository fileUploadRepository;

    @Mock
    private ChunkInfoRepository chunkInfoRepository;

    @Mock
    private MinioConfig minioConfig;

    private UploadService uploadService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        uploadService = new UploadService();
        ReflectionTestUtils.setField(uploadService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(uploadService, "minioClient", minioClient);
        ReflectionTestUtils.setField(uploadService, "fileUploadRepository", fileUploadRepository);
        ReflectionTestUtils.setField(uploadService, "chunkInfoRepository", chunkInfoRepository);
        ReflectionTestUtils.setField(uploadService, "minioConfig", minioConfig);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void uploadChunkRejectsWhenFileAlreadyCompleted() throws Exception {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("md5");
        fileUpload.setUserId("1");
        fileUpload.setStatus(FileUpload.STATUS_COMPLETED);

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        CustomException exception = assertThrows(
                CustomException.class,
                () -> uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1")
        );

        assertEquals("文件已完成合并，不允许继续上传分片", exception.getMessage());
        verifyNoInteractions(chunkInfoRepository);
    }

    @Test
    void uploadChunkRejectsWhenFileIsMerging() throws Exception {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("md5");
        fileUpload.setUserId("1");
        fileUpload.setStatus(FileUpload.STATUS_MERGING);

        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        CustomException exception = assertThrows(
                CustomException.class,
                () -> uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1")
        );

        assertEquals("文件正在合并中，请稍后重试", exception.getMessage());
        verifyNoInteractions(chunkInfoRepository);
    }

    @Test
    void uploadChunkSkipsDatabaseWhenRedisBitmapHit() throws Exception {
        FileUpload fileUpload = uploadingFile();
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));
        when(valueOperations.getBit("upload:1:md5", 0)).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1");

        verifyNoInteractions(chunkInfoRepository);
        verifyNoInteractions(minioClient);
    }

    @Test
    void uploadChunkBackfillsRedisWhenDatabaseHasChunkAfterRedisMiss() throws Exception {
        FileUpload fileUpload = uploadingFile();
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));
        when(valueOperations.getBit("upload:1:md5", 0)).thenReturn(false);
        when(chunkInfoRepository.existsByFileMd5AndChunkIndex("md5", 0)).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1");

        verify(valueOperations).setBit("upload:1:md5", 0, true);
        verifyNoInteractions(minioClient);
    }

    @Test
    void uploadChunkWritesDatabaseBeforeRedisForNewChunk() throws Exception {
        FileUpload fileUpload = uploadingFile();
        when(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc("md5", "1"))
                .thenReturn(Optional.of(fileUpload));
        when(valueOperations.getBit("upload:1:md5", 0)).thenReturn(false);
        when(chunkInfoRepository.existsByFileMd5AndChunkIndex("md5", 0)).thenReturn(false);

        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "demo".getBytes());

        uploadService.uploadChunk("md5", 0, 1024L, "test.pdf", file, "TEAM_A", false, "1");

        InOrder inOrder = inOrder(minioClient, chunkInfoRepository, valueOperations);
        inOrder.verify(minioClient).putObject(any(PutObjectArgs.class));
        inOrder.verify(chunkInfoRepository).save(any(ChunkInfo.class));
        inOrder.verify(valueOperations).setBit("upload:1:md5", 0, true);
    }

    private FileUpload uploadingFile() {
        FileUpload fileUpload = new FileUpload();
        fileUpload.setFileMd5("md5");
        fileUpload.setUserId("1");
        fileUpload.setStatus(FileUpload.STATUS_UPLOADING);
        return fileUpload;
    }
}
