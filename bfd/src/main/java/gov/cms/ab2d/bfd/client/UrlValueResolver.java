package gov.cms.ab2d.bfd.client;

import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.stereotype.Component;
import org.springframework.util.StringValueResolver;

@Component
public class UrlValueResolver implements EmbeddedValueResolverAware {
    private StringValueResolver embeddedValueResolver;

    @Override
    public void setEmbeddedValueResolver(StringValueResolver resolver) {
        this.embeddedValueResolver = resolver;
    }

    public String readMyProperty(String propertyString) {
        if (embeddedValueResolver == null) {
            return "";
        }
        return embeddedValueResolver.resolveStringValue(propertyString);
    }
}