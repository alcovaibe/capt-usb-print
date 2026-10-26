/*
 * CAPT integration of LibreOffice UNO page styles. Included in pinned init.cxx.
 * SPDX-License-Identifier: MPL-2.0
 * No CAPT/HISCOA protocol code, document contents or diagnostics belong here.
 */
#include <jni.h>
#include <com/sun/star/container/XIndexAccess.hpp>
#include <com/sun/star/sheet/XSpreadsheetDocument.hpp>
#include <com/sun/star/view/XRenderable.hpp>
#include <com/sun/star/table/CellRangeAddress.hpp>
#include <vector>
#include <set>

#ifdef ANDROID
extern "C" SAL_JNI_EXPORT jint JNICALL
Java_org_libreoffice_kit_Document_configureCalcFitWidth(JNIEnv* env, jobject object,
                                                      jintArray selected)
{
    // Match the upstream Document ByteBuffer handle; never retain Java references.
    jclass clazz = env->GetObjectClass(object);
    if (!clazz)
        return 0;
    jfieldID field = env->GetFieldID(clazz, "handle", "Ljava/nio/ByteBuffer;");
    env->DeleteLocalRef(clazz);
    if (!field)
        return 0;
    jobject buffer = env->GetObjectField(object, field);
    if (!buffer)
        return 0;
    auto* document = static_cast<LibreOfficeKitDocument*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    env->DeleteLocalRef(buffer);
    if (!document || capacity < static_cast<jlong>(sizeof(LibreOfficeKitDocument)) || !selected)
        return 0;
    const jsize length = env->GetArrayLength(selected);
    if (length < 0 || length > 256)
        return 0;
    std::vector<jint> indices(length);
    if (length)
        env->GetIntArrayRegion(selected, 0, length, indices.data());
    if (env->ExceptionCheck())
        return 0;

    SolarMutexGuard guard;
    try
    {
        if (getDocumentType(document) != LOK_DOCTYPE_SPREADSHEET)
            return 0;
        auto* impl = static_cast<LibLODocument_Impl*>(document);
        uno::Reference<sheet::XSpreadsheetDocument> book(impl->mxComponent, uno::UNO_QUERY_THROW);
        uno::Reference<container::XIndexAccess> sheets(book->getSheets(), uno::UNO_QUERY_THROW);
        const sal_Int32 count = sheets->getCount();
        if (count < 1 || count > 256)
            return 0;
        if (indices.empty())
            for (sal_Int32 i = 0; i < count; ++i)
                indices.push_back(i);
        for (size_t i = 0; i < indices.size(); ++i)
            if (indices[i] < 0 || indices[i] >= count || (i && indices[i] <= indices[i - 1]))
                return 0;

        uno::Reference<style::XStyleFamiliesSupplier> supplier(impl->mxComponent, uno::UNO_QUERY_THROW);
        uno::Reference<container::XNameAccess> styles(
            supplier->getStyleFamilies()->getByName(u"PageStyles"_ustr), uno::UNO_QUERY_THROW);
        struct Page
        {
            uno::Reference<beans::XPropertySet> style;
            sal_Int32 width, height;
            bool landscape;
            sal_Int32 left, right, top, bottom;
        };
        std::vector<Page> pages;
        std::set<OUString> seen;
        // Collect/validate before mutation. A style shared by selected sheets is changed once.
        for (jint index : indices)
        {
            uno::Reference<beans::XPropertySet> sheet(sheets->getByIndex(index), uno::UNO_QUERY_THROW);
            OUString name;
            if (!(sheet->getPropertyValue(u"PageStyle"_ustr) >>= name) || name.isEmpty())
                return 0;
            if (!seen.insert(name).second)
                continue;
            uno::Reference<beans::XPropertySet> style(styles->getByName(name), uno::UNO_QUERY_THROW);
            bool landscape = false;
            sal_Int32 left = 0, right = 0, top = 0, bottom = 0;
            if (!(style->getPropertyValue(u"IsLandscape"_ustr) >>= landscape)
                || !(style->getPropertyValue(u"LeftMargin"_ustr) >>= left)
                || !(style->getPropertyValue(u"RightMargin"_ustr) >>= right)
                || !(style->getPropertyValue(u"TopMargin"_ustr) >>= top)
                || !(style->getPropertyValue(u"BottomMargin"_ustr) >>= bottom))
                return 0;
            const sal_Int32 width = landscape ? 29700 : 21000;
            const sal_Int32 height = landscape ? 21000 : 29700;
            if (left < 0 || right < 0 || top < 0 || bottom < 0
                || static_cast<sal_Int64>(left) + right >= width
                || static_cast<sal_Int64>(top) + bottom >= height)
                return 0;
            pages.push_back({ style, width, height, landscape, left, right, top, bottom });
        }
        for (const auto& page : pages)
        {
            page.style->setPropertyValue(u"IsLandscape"_ustr, uno::Any(page.landscape));
            page.style->setPropertyValue(u"Width"_ustr, uno::Any(page.width));
            page.style->setPropertyValue(u"Height"_ustr, uno::Any(page.height));
            // ScStyleObj clears competing items: the pool defaults are 100% / 0 total pages.
            // Calc gives valid ScaleToPagesX/Y precedence over the default percentage.
            page.style->setPropertyValue(u"ScaleToPagesX"_ustr, uno::Any(sal_Int16(1)));
            page.style->setPropertyValue(u"ScaleToPagesY"_ustr, uno::Any(sal_Int16(0)));
            sal_Int16 x = -1, y = -1, scale = -1, total = -1;
            sal_Int32 width = 0, height = 0, left = 0, right = 0, top = 0, bottom = 0;
            bool landscape = false;
            if (!(page.style->getPropertyValue(u"ScaleToPagesX"_ustr) >>= x)
                || !(page.style->getPropertyValue(u"ScaleToPagesY"_ustr) >>= y)
                || !(page.style->getPropertyValue(u"PageScale"_ustr) >>= scale)
                || !(page.style->getPropertyValue(u"ScaleToPages"_ustr) >>= total)
                || !(page.style->getPropertyValue(u"Width"_ustr) >>= width)
                || !(page.style->getPropertyValue(u"Height"_ustr) >>= height)
                || !(page.style->getPropertyValue(u"IsLandscape"_ustr) >>= landscape)
                || !(page.style->getPropertyValue(u"LeftMargin"_ustr) >>= left)
                || !(page.style->getPropertyValue(u"RightMargin"_ustr) >>= right)
                || !(page.style->getPropertyValue(u"TopMargin"_ustr) >>= top)
                || !(page.style->getPropertyValue(u"BottomMargin"_ustr) >>= bottom)
                || landscape != page.landscape || left != page.left || right != page.right
                || top != page.top || bottom != page.bottom
                || x != 1 || y != 0 || scale != 100 || total != 0
                // UNO converts mm/100 to integer twips and back; allow only that quantization.
                || width < page.width - 2 || width > page.width + 2
                || height < page.height - 2 || height > page.height + 2)
                return 0;
        }
        // Inspect Calc's real page descriptors, not just the requested scale style.
        // At ZOOM_MIN=10 Calc may still split horizontally. Manual column breaks
        // and disjoint print areas can also defeat ScaleToPagesX=1. Reject such a
        // layout before export; never silently call it fitted or shrink height.
        uno::Reference<view::XRenderable> render(impl->mxComponent, uno::UNO_QUERY_THROW);
        const uno::Sequence<beans::PropertyValue> options{
            comphelper::makePropertyValue(u"IsPrinter"_ustr, false),
            comphelper::makePropertyValue(u"IsSuppressEmptyPages"_ustr, true),
            comphelper::makePropertyValue(u"SinglePageSheets"_ustr, false)
        };
        sal_Int32 totalPages = 0;
        for (jint index : indices)
        {
            const uno::Any selection = sheets->getByIndex(index);
            uno::Reference<beans::XPropertySet> sheet(selection, uno::UNO_QUERY_THROW);
            OUString name;
            if (!(sheet->getPropertyValue(u"PageStyle"_ustr) >>= name))
                return 0;
            uno::Reference<beans::XPropertySet> style(styles->getByName(name), uno::UNO_QUERY_THROW);
            sal_Int32 width = 0, left = 0, right = 0;
            if (!(style->getPropertyValue(u"Width"_ustr) >>= width)
                || !(style->getPropertyValue(u"LeftMargin"_ustr) >>= left)
                || !(style->getPropertyValue(u"RightMargin"_ustr) >>= right))
                return 0;
            const sal_Int32 countPages = render->getRendererCount(selection, options);
            if (countPages < 1 || countPages > 1000 || totalPages > 1000 - countPages)
                return -1;
            totalPages += countPages;
            sal_Int32 firstColumn = -1, lastColumn = -1;
            for (sal_Int32 page = 0; page < countPages; ++page)
            {
                table::CellRangeAddress range;
                awt::Size size;
                bool hasRange = false, hasSize = false;
                for (const auto& property : render->getRenderer(page, selection, options))
                {
                    if (property.Name == u"SourceRange"_ustr)
                        hasRange = (property.Value >>= range);
                    else if (property.Name == u"CalcPageSize"_ustr)
                        hasSize = (property.Value >>= size);
                }
                // Without source geometry we cannot prove width, including a
                // single overwide column that Calc might otherwise clip.
                if (!hasRange || !hasSize || range.Sheet != index || size.Width <= 0
                    || range.StartColumn < 0 || range.EndColumn < range.StartColumn
                    || static_cast<sal_Int64>(size.Width) >
                        static_cast<sal_Int64>(width - left - right) * 10 + 10)
                    return -1;
                if (page == 0)
                {
                    firstColumn = range.StartColumn;
                    lastColumn = range.EndColumn;
                }
                else if (range.StartColumn != firstColumn || range.EndColumn != lastColumn)
                    return -1;
            }
        }
        return pages.empty() ? 0 : 1;
    }
    catch (const uno::Exception&)
    {
        // Do not forward document text, style names, URLs or UNO exception messages.
        return 0;
    }
}
#endif
