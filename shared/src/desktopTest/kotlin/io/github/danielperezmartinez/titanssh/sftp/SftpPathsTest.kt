package io.github.danielperezmartinez.titanssh.sftp

import io.github.danielperezmartinez.titanssh.ssh.SftpEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SftpPathsTest {

    @Test
    fun initial_folder_hangs_relative_and_tilde_paths_from_home() {
        val home = "/home/demo"
        assertEquals(home, SftpPaths.initial(null, home))
        assertEquals(home, SftpPaths.initial("  ", home))
        assertEquals(home, SftpPaths.initial("~", home))
        assertEquals("/home/demo/proyecto", SftpPaths.initial("~/proyecto", home))
        assertEquals("/home/demo/proyecto/src", SftpPaths.initial("proyecto/src/", home))
        assertEquals("/srv/app", SftpPaths.initial("/srv/app", home))
    }

    @Test
    fun initial_folder_turns_a_windows_path_into_its_sftp_form() {
        val home = "/C:/Users/demo"
        assertEquals("/D:/datos/logs", SftpPaths.initial("D:\\datos\\logs", home))
        assertEquals("/C:/", SftpPaths.initial("C:\\", home))
        assertEquals("/C:/Users/demo/proyecto", SftpPaths.initial("~\\proyecto", home))
    }

    @Test
    fun parent_walks_up_to_the_root_and_keeps_a_drive_root_listable() {
        assertEquals("/srv", SftpPaths.parent("/srv/app"))
        assertEquals("/", SftpPaths.parent("/srv"))
        assertNull(SftpPaths.parent("/"))
        assertEquals("/C:/", SftpPaths.parent("/C:/Users"))
        assertEquals("/", SftpPaths.parent("/C:/"))
    }

    @Test
    fun crumbs_name_each_folder_down_to_the_path() {
        assertEquals(listOf("/" to "/", "srv" to "/srv", "app" to "/srv/app"), SftpPaths.crumbs("/srv/app"))
        assertEquals(listOf("/" to "/"), SftpPaths.crumbs("/"))
        assertEquals(listOf("/" to "/", "C:" to "/C:/", "Users" to "/C:/Users"), SftpPaths.crumbs("/C:/Users"))
    }

    @Test
    fun sorted_puts_folders_first_and_hides_dot_files_unless_asked() {
        fun entry(name: String, kind: SftpEntry.Kind) = SftpEntry(name, "/x/$name", kind, 1, null, null)
        val entries = listOf(
            entry("b.txt", SftpEntry.Kind.FILE),
            entry(".bashrc", SftpEntry.Kind.FILE),
            entry("Zeta", SftpEntry.Kind.DIRECTORY),
            entry("alfa", SftpEntry.Kind.LINK_TO_DIRECTORY),
            entry("A.txt", SftpEntry.Kind.FILE),
        )
        assertEquals(listOf("alfa", "Zeta", "A.txt", "b.txt"), SftpPaths.sorted(entries, showHidden = false).map { it.name })
        assertEquals(".bashrc", SftpPaths.sorted(entries, showHidden = true)[2].name)
    }

    @Test
    fun sizes_and_permissions_read_like_ls() {
        assertEquals("512 B", SftpPaths.formatSize(512))
        assertEquals("1.5 KB", SftpPaths.formatSize(1536))
        assertEquals("10 MB", SftpPaths.formatSize(10L * 1024 * 1024))
        assertEquals("rwxr-xr-x", SftpPaths.formatPermissions(0b111_101_101))
        assertEquals("rw-------", SftpPaths.formatPermissions(0b110_000_000))
    }

    @Test
    fun local_file_names_are_numbered_and_made_safe() {
        assertEquals("informe (1).pdf", numberedFileName("informe.pdf", 1))
        assertEquals(".bashrc (2)", numberedFileName(".bashrc", 2))
        assertEquals("LEEME (3)", numberedFileName("LEEME", 3))
        assertEquals("a_b_c.txt", safeFileName("a:b?c.txt"))
        assertEquals("descarga", safeFileName("..."))
    }
}
