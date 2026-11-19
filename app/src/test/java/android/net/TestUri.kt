package android.net

import android.os.Parcel

class TestUri(private val uriString: String = "file:///fake.jpg") : Uri() {
    override fun toString(): String = uriString
    override fun getPath(): String = uriString
    override fun isHierarchical(): Boolean = false
    override fun isOpaque(): Boolean = false
    override fun isAbsolute(): Boolean = true
    override fun isRelative(): Boolean = false
    override fun getScheme(): String = "file"
    override fun getSchemeSpecificPart(): String = uriString
    override fun getEncodedSchemeSpecificPart(): String = uriString
    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String? = null
    override fun getPort(): Int = -1
    override fun getEncodedPath(): String = uriString
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): MutableList<String> = mutableListOf()
    override fun getLastPathSegment(): String? = null
    override fun buildUpon(): Builder? = null
    override fun compareTo(other: Uri?): Int = 0
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) {
        // Test stub: parceling is not required in unit tests
    }
}
