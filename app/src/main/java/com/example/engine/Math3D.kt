package com.example.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f
) {
    operator fun plus(v: Vec3) = Vec3(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: Vec3) = Vec3(x - v.x, y - v.y, z - v.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)

    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun lengthSquared(): Float = x * x + y * y + z * z

    fun normalized(): Vec3 {
        val len = length()
        return if (len > 0.00001f) Vec3(x / len, y / len, z / len) else Vec3(0f, 0f, 0f)
    }

    fun dot(v: Vec3): Float = x * v.x + y * v.y + z * v.z

    fun cross(v: Vec3): Vec3 = Vec3(
        y * v.z - z * v.y,
        z * v.x - x * v.z,
        x * v.y - y * v.x
    )
}

data class Vec2(
    var x: Float = 0f,
    var y: Float = 0f
) {
    operator fun plus(v: Vec2) = Vec2(x + v.x, y + v.y)
    operator fun minus(v: Vec2) = Vec2(x - v.x, y - v.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    fun length(): Float = sqrt(x * x + y * y)
}

class Mat4 {
    val m = FloatArray(16)

    init {
        identity()
    }

    fun identity(): Mat4 {
        for (i in 0..15) m[i] = 0f
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return this
    }

    companion object {
        fun identity(): Mat4 = Mat4()

        fun translation(tx: Float, ty: Float, tz: Float): Mat4 {
            val mat = Mat4()
            mat.m[12] = tx
            mat.m[13] = ty
            mat.m[14] = tz
            return mat
        }

        fun scale(sx: Float, sy: Float, sz: Float): Mat4 {
            val mat = Mat4()
            mat.m[0] = sx
            mat.m[5] = sy
            mat.m[10] = sz
            return mat
        }

        fun rotationY(angleRad: Float): Mat4 {
            val mat = Mat4()
            val c = cos(angleRad)
            val s = sin(angleRad)
            mat.m[0] = c
            mat.m[2] = s
            mat.m[8] = -s
            mat.m[10] = c
            return mat
        }

        fun rotationX(angleRad: Float): Mat4 {
            val mat = Mat4()
            val c = cos(angleRad)
            val s = sin(angleRad)
            mat.m[5] = c
            mat.m[6] = -s
            mat.m[9] = s
            mat.m[10] = c
            return mat
        }

        fun rotationZ(angleRad: Float): Mat4 {
            val mat = Mat4()
            val c = cos(angleRad)
            val s = sin(angleRad)
            mat.m[0] = c
            mat.m[1] = -s
            mat.m[4] = s
            mat.m[5] = c
            return mat
        }

        fun perspective(fovYRad: Float, aspect: Float, near: Float, far: Float): Mat4 {
            val mat = Mat4()
            for (i in 0..15) mat.m[i] = 0f
            val f = 1.0f / kotlin.math.tan(fovYRad / 2.0f)
            mat.m[0] = f / aspect
            mat.m[5] = f
            mat.m[10] = (far + near) / (near - far)
            mat.m[11] = -1f
            mat.m[14] = (2f * far * near) / (near - far)
            return mat
        }

        fun lookAt(eye: Vec3, target: Vec3, up: Vec3): Mat4 {
            val f = (target - eye).normalized()
            val s = f.cross(up).normalized()
            val u = s.cross(f)

            val mat = Mat4()
            mat.m[0] = s.x
            mat.m[4] = s.y
            mat.m[8] = s.z
            mat.m[12] = -s.dot(eye)

            mat.m[1] = u.x
            mat.m[5] = u.y
            mat.m[9] = u.z
            mat.m[13] = -u.dot(eye)

            mat.m[2] = -f.x
            mat.m[6] = -f.y
            mat.m[10] = -f.z
            mat.m[14] = f.dot(eye)

            mat.m[3] = 0f
            mat.m[7] = 0f
            mat.m[11] = 0f
            mat.m[15] = 1f

            return mat
        }
    }

    fun multiply(b: Mat4): Mat4 {
        val result = Mat4()
        for (row in 0..3) {
            for (col in 0..3) {
                var sum = 0f
                for (i in 0..3) {
                    sum += this.m[row + i * 4] * b.m[i + col * 4]
                }
                result.m[row + col * 4] = sum
            }
        }
        return result
    }

    fun transformPoint(v: Vec3): Vec3 {
        val x = v.x * m[0] + v.y * m[4] + v.z * m[8] + m[12]
        val y = v.x * m[1] + v.y * m[5] + v.z * m[9] + m[13]
        val z = v.x * m[2] + v.y * m[6] + v.z * m[10] + m[14]
        val w = v.x * m[3] + v.y * m[7] + v.z * m[11] + m[15]

        return if (w != 0f) Vec3(x / w, y / w, z / w) else Vec3(x, y, z)
    }

    fun transformDirection(v: Vec3): Vec3 {
        val x = v.x * m[0] + v.y * m[4] + v.z * m[8]
        val y = v.x * m[1] + v.y * m[5] + v.z * m[9]
        val z = v.x * m[2] + v.y * m[6] + v.z * m[10]
        return Vec3(x, y, z)
    }
}
