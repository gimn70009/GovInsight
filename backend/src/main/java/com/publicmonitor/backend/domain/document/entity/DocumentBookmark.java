package com.publicmonitor.backend.domain.document.entity;

import com.publicmonitor.backend.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "document_bookmarks", uniqueConstraints = @UniqueConstraint(name = "uk_bookmark_user_version", columnNames = {"user_id", "version_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentBookmark {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "bookmark_sequence")
    @SequenceGenerator(name = "bookmark_sequence", sequenceName = "document_bookmarks_sequence", allocationSize = 1)
    @Column(name = "bookmark_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private DocumentVersion version;

    public static DocumentBookmark create(User user, DocumentVersion version) {
        DocumentBookmark bookmark = new DocumentBookmark();
        bookmark.user = user;
        bookmark.version = version;
        return bookmark;
    }
}
